# PodClatch on AWS

Two CloudFormation templates put PodClatch Server on AWS behind an HTTPS address:

- `bootstrap.yaml` (stack `podclatch-bootstrap`, deployed once): the GitHub OIDC identity provider, the ECR repository `podclatch-server`, and a least-privilege role for the pipeline.
- `app.yaml` (stacks `podclatch-dev` and `podclatch-prod`): the server on Amazon ECS Express Mode (Fargate behind a managed load balancer), PostgreSQL on RDS, and three secrets in Secrets Manager. One file, two stacks, chosen by the `Environment` parameter.

**Status: shut down on 2026-11-13.** The templates worked and were deployed to `dev` on 2026-11-08 and to `prod` on 2026-11-10 (see the README, Deployment), then every stack was deleted on 2026-11-13 to avoid the ongoing cost (section 10 was followed, and nothing from the project is left in the account). The templates are unchanged and still checked statically (`cfn-lint`, the tests in `infra/tests/`), so you can deploy them again by following this page. The product will be updated later to work without a server component. You run every deploy command below yourself, in an AWS account that is yours; an agent should only read from the account. Never run them against an account that is not yours.

Why ECS Express Mode: it gives a valid `https://<name>.ecs.<region>.on.aws` address, with a managed certificate and load balancer and no domain name work. The release build of the Android app refuses plain HTTP to anything but `localhost`, `127.0.0.1`, and `10.0.2.2`, so HTTPS is required. The design first used AWS App Runner, but AWS closed App Runner to new customers on 2026-04-30 (the first real deploy failed with "Account ... can't create new App Runner services"), and recommends Express Mode as the replacement. The cost of Express Mode is higher, because the load balancer is not free (see below).

## 1. What gets created, and what it costs

Estimate made on 2026-11-08 for `us-east-1`, from list prices as the author understood them. It is an estimate and has not been checked against live prices or a bill. Prices change; check the AWS pricing pages and your billing page.

| Item | Per environment, per month |
|---|---|
| RDS PostgreSQL `db.t4g.micro`, single AZ | about 13 dollars |
| RDS storage, 20 GB gp3 | about 2.50 dollars |
| Fargate, one task of 0.25 vCPU and 0.5 GB, running all the time | about 9 dollars |
| Application Load Balancer (created by Express Mode; one is shared by up to 25 Express services in the VPC) | about 18 to 22 dollars |
| Public IPv4 addresses: the RDS instance, the task, and the load balancer's two | about 14.60 dollars |
| Secrets Manager, 3 secrets | about 1.20 dollars |
| CloudWatch logs, ECR storage, and the bootstrap role | pennies |
| **Total** | **about 58 to 62 dollars** (about 2 dollars a day) |

Two environments cost more (roughly 40 dollars a month more for `prod`, about 3.40 dollars a day for both), but they share one load balancer. The cheapest path to a working demo is to deploy `dev` only, and then tear it down (section 10). `prod` is optional; it was deployed once, on 2026-11-10, to run the Promote workflow for real. A monthly budget alert of 30 dollars fires after roughly two weeks of leaving `dev` running, sooner with both.

The bootstrap stack itself costs almost nothing. The ECS service and its load balancer, the RDS instance, and the three secrets (the access token, the database password, and the complete `DATABASE_URL`) are created per app stack.

## 2. One-time setup (you)

1. Create or use an AWS account, and turn on MFA for the root user (console, account menu, Security credentials).
2. Create an admin user or an IAM Identity Center (SSO) login for yourself and use it from now on, not root.
3. In Billing, create a budget alert (for example 30 dollars a month) that emails you.
4. Install the AWS CLI v2 and sign in: `aws configure sso`, then `aws sso login --profile <profile>`. Use `--profile <profile>` (or `AWS_PROFILE`) on every command below, and `--region us-east-1` (or `AWS_REGION`) if your profile has no default region.
5. Check that you are in the right account before anything else: `aws sts get-caller-identity`.

## 3. Deploy the bootstrap stack

From the repository root:

```
aws cloudformation deploy \
  --stack-name podclatch-bootstrap \
  --template-file infra/bootstrap.yaml \
  --capabilities CAPABILITY_NAMED_IAM
```

If the account already has a GitHub OIDC provider (an account can have only one), add `--parameter-overrides CreateOidcProvider=false ExistingOidcProviderArn=<its ARN>`. The role trusts only `<GitHubSubjectPrefix>:environment:dev` and `<GitHubSubjectPrefix>:environment:production`, where the prefix defaults to this repository's OIDC subject, `repo:jdobbelaar@7696911/podclatch@1422992323`. That is GitHub's "immutable subject" form, with the numeric owner and repository IDs, which newer repositories use; the classic `repo:<owner>/<name>` form would be refused by GitHub's token and fail the login with "Not authorized to perform sts:AssumeRoleWithWebIdentity". To use a fork, find the prefix with `gh api repos/<owner>/<name>/actions/oidc/customization/sub` (use `sub_claim_prefix` if `use_immutable_subject` is true, otherwise `repo:<owner>/<name>`) and add `GitHubSubjectPrefix=<prefix>` to the overrides.

Note the three outputs:

```
aws cloudformation describe-stacks --stack-name podclatch-bootstrap \
  --query "Stacks[0].Outputs" --output table
```

`DeployRoleArn` goes into the GitHub repository variable `AWS_DEPLOY_ROLE_ARN` (the pipeline story uses it). `EcrRepositoryUri` is used in section 4.

## 4. Push the first image by hand

ECS will not start a service whose image tag does not exist, so the first image goes in before the app stack. Build with a tag of your own: do not reuse `podclatch-server:local`, which belongs to the local Compose stack.

```
REGISTRY=<account id>.dkr.ecr.us-east-1.amazonaws.com
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $REGISTRY
docker build --platform linux/amd64 -t podclatch-server:aws server/
docker tag podclatch-server:aws $REGISTRY/podclatch-server:dev
docker push $REGISTRY/podclatch-server:dev
```

(`<account id>` is in the `EcrRepositoryUri` output.) After this the pipeline keeps the tags moving: it pushes the image under the commit SHA and moves `dev` (or `prod`) to it.

## 5. Deploy the `dev` stack

The database goes in the region's default VPC. Find its ID and two or more subnets in different Availability Zones (default subnets are public, which the database needs):

```
aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query "Vpcs[0].VpcId" --output text
aws ec2 describe-subnets --filters Name=vpc-id,Values=<vpc id> \
  --query "Subnets[].[SubnetId,AvailabilityZone]" --output text
```

Then deploy (this takes about 10 to 15 minutes, mostly the database):

```
aws cloudformation deploy \
  --stack-name podclatch-dev \
  --template-file infra/app.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides Environment=dev VpcId=<vpc id> SubnetIds=<subnet id 1>,<subnet id 2>
```

(`CAPABILITY_NAMED_IAM`, not `CAPABILITY_IAM`: the two ECS roles have fixed names so the deploy role can be allowed to pass exactly those.) The default VPC must exist; if the account has none, `aws ec2 create-default-vpc` creates it.

Optional parameters: `ImageTag` (default: the environment name), `EcrRepositoryName` (default `podclatch-server`), `RefreshIntervalHours` (default 3). For `prod`, use `--stack-name podclatch-prod` and `Environment=prod` (and push a `prod` image tag first). Read the outputs:

```
aws cloudformation describe-stacks --stack-name podclatch-dev \
  --query "Stacks[0].Outputs" --output table
```

`ServiceUrl` is the HTTPS address. Put it in the GitHub repository variable `DEV_URL` (`PROD_URL` for prod).

## 6. Read the access token

The token is generated by Secrets Manager and appears nowhere in the repository. Read it once, type it into the app, and do not paste it into an issue, chat, or log:

```
aws secretsmanager get-secret-value --secret-id <TokenSecretArn output> \
  --query SecretString --output text
```

## 7. Check the deployment

```
curl -i <ServiceUrl>/healthz
curl -i <ServiceUrl>/v1/feeds
curl -i -H "Authorization: Bearer <token>" <ServiceUrl>/v1/feeds
```

The first returns 200 and `{"status":"ok"}`. The second, without the token, returns 401. The third, with it, returns 200 and a list. Then, in the app, enter the `ServiceUrl` and the token in the server settings of a **release** build on a physical phone (a debug build is an acceptable first check; say which one you used), press Test connection, and run a Sync.

## 8. Updating

The pipeline pushes the new image under its commit SHA (and moves the `dev` or `prod` tag to it), then points the service at the SHA image with `aws ecs update-express-gateway-service`; Express Mode does not watch a tag, so that call is what starts the deployment. By hand, push an image with a new tag as in section 4 and run `python3 .github/scripts/ci_tools.py update-service --name podclatch-dev --image <registry>/podclatch-server:<tag>` (with `AWS_REGION` set). To change a template, edit it and run the same `aws cloudformation deploy` command again; the pipeline has no CloudFormation permission on purpose. A template deploy puts the service back on the `dev` tag image.

## 9. Pausing and stopping to save money

- There is no pause for the server or its load balancer; the only way to stop their charge is to delete the stack (section 10) and deploy it again later.
- Stop the database: `aws rds stop-db-instance --db-instance-identifier podclatch-dev`, and `aws rds start-db-instance --db-instance-identifier podclatch-dev`. RDS starts a stopped instance again by itself after 7 days. Storage and backups are still billed while it is stopped.

## 10. Tear-down, in this order

1. Delete the app stacks. `dev`: `aws cloudformation delete-stack --stack-name podclatch-dev`, then `aws cloudformation wait stack-delete-complete --stack-name podclatch-dev`. `prod` has deletion protection on: first run `aws rds modify-db-instance --db-instance-identifier podclatch-prod --no-deletion-protection --apply-immediately`, then delete the stack the same way. It leaves a final snapshot; delete it with `aws rds delete-db-snapshot` when you are sure.
2. Secrets Manager keeps a deleted secret for 30 days by default and holds its name. If you will deploy again soon, or want it gone, remove each now: `aws secretsmanager delete-secret --secret-id podclatch/dev/token --force-delete-without-recovery`, and the same for `podclatch/dev/database-password` and `podclatch/dev/database-url`. Then check `aws secretsmanager list-secrets --include-planned-deletion` for leftovers.
3. Empty the ECR repository, then delete the bootstrap stack (CloudFormation cannot delete a repository that holds images):

   ```
   aws ecr list-images --repository-name podclatch-server --query "imageIds[*]" --output json > image-ids.json
   aws ecr batch-delete-image --repository-name podclatch-server --image-ids file://image-ids.json
   aws cloudformation delete-stack --stack-name podclatch-bootstrap
   aws cloudformation wait stack-delete-complete --stack-name podclatch-bootstrap
   ```

   (`batch-delete-image` takes up to 100 images at a time; repeat if the repository held more. Delete `image-ids.json` afterwards.)
4. The bootstrap stack removes the OIDC provider if it created it. If something else in the account uses that provider, deploy the bootstrap stack with `CreateOidcProvider=false` first (so the provider is not part of the stack), and then delete the stack.
5. In the console, confirm that no stack, RDS instance or snapshot, ECS service, load balancer, or ECR repository of the project is left (the empty `default` ECS cluster is free and can stay; delete it with `aws ecs delete-cluster --cluster default` if you want a clean account), look for a leftover CloudWatch log group named `/aws/ecs/default/podclatch-*`, and check the bill the next day.

## Things to know

- **The database is reachable from the internet.** The service's tasks run in public subnets and get public addresses that change, so the database security group allows port 5432 from anywhere. It is protected by forced TLS (`rds.force_ssl=1`, and the server connects with `sslmode=require`) and a 32-character generated password only. The alternative, private subnets, would need a NAT gateway at about 32 dollars a month. This is acceptable for a single-user, throw-away demo and is not something to keep running long term.
- **One task, always on.** Express Mode scales on CPU, and the template fixes the service at one task of 0.25 vCPU and 0.5 GB, so the server's own scheduled refresh (every 3 hours) runs once and is not throttled, unlike on App Runner. A deployment briefly runs a second task while the new one becomes healthy.
- **Secrets.** The token, the database password, and the `DATABASE_URL` are generated or built by Secrets Manager, reach the service as runtime secrets (not plain environment variables), and are never in the repository. The ECS execution role can read only the token and the URL.
- **Deploy role.** It can log in to ECR, push, pull, and retag images in `podclatch-server`, update and describe ECS Express services named `podclatch-*`, register task definitions, and pass exactly the two Express roles (`podclatch-<env>-ecs-execution` and `-ecs-infrastructure`) to ECS. It cannot create or delete a service, and has no CloudFormation or RDS permission.
- **Pinned database version.** The template pins PostgreSQL 17.6 because the major version alone is not accepted for new instances. With automatic minor upgrades on, RDS moves it forward. If CloudFormation reports that 17.6 is no longer offered, run `aws rds describe-db-engine-versions --engine postgres --query "DBEngineVersions[].EngineVersion"` and change `EngineVersion` in `app.yaml`.
- **The checks** (`cfn-lint` and `infra/tests/`) run in the "Infra CI" workflow and need no AWS account. Locally: `uvx cfn-lint infra/bootstrap.yaml infra/app.yaml`, and `uv run --no-project --with pytest --with pyyaml pytest infra/tests`.
