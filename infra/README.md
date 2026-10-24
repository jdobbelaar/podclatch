# PodClatch on AWS

Two CloudFormation templates put PodClatch Server on AWS behind an HTTPS address:

- `bootstrap.yaml` (stack `podclatch-bootstrap`, deployed once): the GitHub OIDC identity provider, the ECR repository `podclatch-server`, and a least-privilege role for the pipeline.
- `app.yaml` (stacks `podclatch-dev` and `podclatch-prod`): the server on AWS App Runner, PostgreSQL on RDS, and three secrets in Secrets Manager. One file, two stacks, chosen by the `Environment` parameter.

**No agent has deployed anything, and none has AWS credentials.** The templates were only checked statically (`cfn-lint`, the tests in `infra/tests/`). You run every command below yourself, in an AWS account that is yours. Never run them against an account that is not yours.

Why App Runner: it gives a valid `https://<id>.<region>.awsapprunner.com` address with no domain name or certificate work. The release build of the Android app refuses plain HTTP to anything but `localhost`, `127.0.0.1`, and `10.0.2.2`, so HTTPS is required. A load balancer would need a domain and cost about 16 dollars a month more.

## 1. What gets created, and what it costs

Estimate made on 2026-10-24 for `us-east-1`, from list prices as the story's author understood them. It is an estimate and has not been checked against live prices. Prices change; check the AWS pricing pages and your billing page.

| Item | Per environment, per month |
|---|---|
| RDS PostgreSQL `db.t4g.micro`, single AZ | about 13 dollars |
| RDS storage, 20 GB gp3 | about 2.50 dollars |
| App Runner, one instance of 0.25 vCPU and 0.5 GB (provisioned memory plus active use) | about 4 to 8 dollars |
| Public IPv4 address of the publicly accessible RDS instance | about 3.65 dollars |
| Secrets Manager, 3 secrets | about 1.20 dollars |
| ECR storage and the bootstrap role | pennies |
| **Total** | **about 22 to 29 dollars** |

Two environments cost about double. The cheapest path to a working demo is to deploy `dev` only, for the demo and grading, and then tear it down (section 10). `prod` is written and checked but need not be deployed.

The bootstrap stack itself costs almost nothing. The App Runner service, the RDS instance, and the three secrets (the access token, the database password, and the complete `DATABASE_URL`) are created per app stack.

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

If the account already has a GitHub OIDC provider (an account can have only one), add `--parameter-overrides CreateOidcProvider=false ExistingOidcProviderArn=<its ARN>`. The role trusts only `repo:jdobbelaar/podclatch:environment:dev` and `repo:jdobbelaar/podclatch:environment:production`; to use a fork, add `GitHubRepository=<owner>/<name>` to the overrides.

Note the three outputs:

```
aws cloudformation describe-stacks --stack-name podclatch-bootstrap \
  --query "Stacks[0].Outputs" --output table
```

`DeployRoleArn` goes into the GitHub repository variable `AWS_DEPLOY_ROLE_ARN` (the pipeline story uses it). `EcrRepositoryUri` is used in section 4.

## 4. Push the first image by hand

App Runner will not create a service whose image tag does not exist, so the first image goes in before the app stack. Build with a tag of your own: do not reuse `podclatch-server:local`, which belongs to the local Compose stack.

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
  --capabilities CAPABILITY_IAM \
  --parameter-overrides Environment=dev VpcId=<vpc id> SubnetIds=<subnet id 1>,<subnet id 2>
```

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

The pipeline moves the image tag (`dev` or `prod`) to a new image, and App Runner, which watches the tag, deploys it. By hand, repeat the `docker build`, `docker tag`, and `docker push` of section 4 with the tag you want to move. To change a template, edit it and run the same `aws cloudformation deploy` command again; the pipeline has no CloudFormation permission on purpose.

## 9. Pausing and stopping to save money

- Pause the server (stops the App Runner charge for compute while paused): `aws apprunner pause-service --service-arn <ServiceArn output>`, and `aws apprunner resume-service --service-arn <ServiceArn output>` to bring it back.
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
5. In the console, confirm that no stack, RDS instance or snapshot, App Runner service, or ECR repository of the project is left, and check the bill the next day.

## Things to know

- **The database is reachable from the internet.** App Runner without a VPC connector has no fixed outbound addresses, so the database security group allows port 5432 from anywhere. It is protected by forced TLS (`rds.force_ssl=1`, and the server connects with `sslmode=require`) and a 32-character generated password only. The alternative, a VPC connector with private subnets, sends all of the service's outbound traffic through a NAT gateway at about 32 dollars a month. This is acceptable for a single-user, throw-away demo and is not something to keep running long term.
- **App Runner throttles an idle instance's CPU.** The server's own scheduled refresh (every 3 hours) may not run until a request arrives. The deployed server is a demo; the app's pull-down refresh works.
- **Secrets.** The token, the database password, and the `DATABASE_URL` are generated or built by Secrets Manager, reach the service as runtime secrets (not plain environment variables), and are never in the repository. The instance role can read only the token and the URL.
- **Deploy role.** It can log in to ECR, push, pull, and retag images in `podclatch-server`, and describe App Runner services named `podclatch-*`. It has no CloudFormation, IAM, or RDS permission.
- **Pinned database version.** The template pins PostgreSQL 17.6 because the major version alone is not accepted for new instances. With automatic minor upgrades on, RDS moves it forward. If CloudFormation reports that 17.6 is no longer offered, run `aws rds describe-db-engine-versions --engine postgres --query "DBEngineVersions[].EngineVersion"` and change `EngineVersion` in `app.yaml`.
- **The checks** (`cfn-lint` and `infra/tests/`) run in the "Infra CI" workflow and need no AWS account. Locally: `uvx cfn-lint infra/bootstrap.yaml infra/app.yaml`, and `uv run --no-project --with pytest --with pyyaml pytest infra/tests`.
