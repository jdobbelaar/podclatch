"""Static checks of the CloudFormation templates (no AWS account, no network).

Run from the repository root:
    uv run --no-project --with pytest --with pyyaml pytest infra/tests
"""

import re
from pathlib import Path

import pytest
import yaml

INFRA = Path(__file__).resolve().parent.parent
GITHUB_REPOSITORY = "jdobbelaar/podclatch"


class _Loader(yaml.SafeLoader):
    pass


def _intrinsic(loader, tag_suffix, node):
    name = "Ref" if tag_suffix == "Ref" else f"Fn::{tag_suffix}"
    if isinstance(node, yaml.ScalarNode):
        value = loader.construct_scalar(node)
        if tag_suffix == "GetAtt":
            value = value.split(".", 1)
    elif isinstance(node, yaml.SequenceNode):
        value = loader.construct_sequence(node, deep=True)
    else:
        value = loader.construct_mapping(node, deep=True)
    return {name: value}


_Loader.add_multi_constructor("!", _intrinsic)


def load(name):
    return yaml.load((INFRA / name).read_text(encoding="utf-8"), Loader=_Loader)


def walk(node):
    """Yield every node (dicts, lists, and scalars) of a template."""
    yield node
    if isinstance(node, dict):
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)


REFERENCE_KEYS = {"Ref", "Fn::GetAtt", "Condition", "DependsOn", "Sid"}


def literals(node):
    """String values that are real literals: names of parameters and resources are skipped."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for key, value in node.items():
            if key not in REFERENCE_KEYS:
                yield from literals(value)
    elif isinstance(node, list):
        for value in node:
            yield from literals(value)


def strings(node):
    return [n for n in walk(node) if isinstance(n, str)]


def render(template, params):
    """Resolve conditions and Fn::If for the given parameter values; drop disabled resources."""
    conditions = {}

    def evaluate(expr):
        if isinstance(expr, dict) and "Fn::Equals" in expr:
            left, right = (resolve(v) for v in expr["Fn::Equals"])
            return left == right
        if isinstance(expr, dict) and "Fn::Not" in expr:
            return not evaluate(expr["Fn::Not"][0])
        if isinstance(expr, dict) and "Condition" in expr:
            return conditions[expr["Condition"]]
        raise AssertionError(f"unsupported condition {expr!r}")

    def resolve(node):
        if isinstance(node, dict):
            if set(node) == {"Ref"} and node["Ref"] in params:
                return params[node["Ref"]]
            if set(node) == {"Fn::If"}:
                name, yes, no = node["Fn::If"]
                return resolve(yes if conditions[name] else no)
            return {k: resolve(v) for k, v in node.items()}
        if isinstance(node, list):
            return [resolve(v) for v in node]
        return node

    for name, expr in template.get("Conditions", {}).items():
        conditions[name] = evaluate(expr)
    out = dict(template)
    out["Resources"] = {
        name: resolve(res)
        for name, res in template["Resources"].items()
        if res.get("Condition") is None or conditions[res["Condition"]]
    }
    return out


def defaults(template):
    return {k: v.get("Default") for k, v in template["Parameters"].items() if "Default" in v}


@pytest.fixture(scope="module")
def bootstrap():
    return load("bootstrap.yaml")


@pytest.fixture(scope="module")
def app():
    return load("app.yaml")


def resources_of(template, type_name):
    return {n: r for n, r in template["Resources"].items() if r["Type"] == type_name}


def test_no_secret_looking_literals(bootstrap, app):
    pattern = re.compile(r"^[A-Za-z0-9]{20,}$")
    forbidden = re.compile(r"AWS_SECRET_ACCESS_KEY|AKIA[0-9A-Z]{16}|\b\d{12}\b", re.IGNORECASE)
    for template in (bootstrap, app):
        for text in (*literals(template["Resources"]), *literals(template.get("Outputs", {}))):
            assert not pattern.match(text), f"secret-looking literal: {text[:6]}..."
            assert not forbidden.search(text), f"forbidden literal: {text[:12]}..."
        for param in template["Parameters"].values():
            default = param.get("Default")
            if isinstance(default, str):
                assert not pattern.match(default)
    raw = (INFRA / "app.yaml").read_text(encoding="utf-8") + (INFRA / "bootstrap.yaml").read_text(
        encoding="utf-8"
    )
    for line in raw.splitlines():
        match = re.match(r"\s*(password|token)\s*:\s*(\S.*)$", line, re.IGNORECASE)
        assert not match, f"literal credential assignment: {line.split(':')[0]}"


def test_every_secret_is_generated_or_dynamic(app):
    secrets = resources_of(app, "AWS::SecretsManager::Secret")
    assert len(secrets) == 3
    for name, secret in secrets.items():
        props = secret["Properties"]
        if "GenerateSecretString" in props:
            assert "SecretString" not in props
            continue
        assert "{{resolve:secretsmanager:" in str(props["SecretString"]), name


def test_generated_secrets_have_the_decided_shape(app):
    secrets = app["Resources"]
    token = secrets["TokenSecret"]["Properties"]["GenerateSecretString"]
    password = secrets["DatabasePasswordSecret"]["Properties"]["GenerateSecretString"]
    assert token["PasswordLength"] == 40
    assert password["PasswordLength"] == 32
    # Letters and digits only: the password goes inside DATABASE_URL.
    assert token["ExcludePunctuation"] is True
    assert password["ExcludePunctuation"] is True


def test_database_url_requires_tls(app):
    url = str(app["Resources"]["DatabaseUrlSecret"]["Properties"]["SecretString"])
    assert url.startswith("{'Fn::Sub': ['postgresql://")
    assert "sslmode=require" in url


def test_service_is_an_ecs_express_mode_service(app):
    assert resources_of(app, "AWS::ECS::ExpressGatewayService").keys() == {"Service"}
    # App Runner is closed to new accounts, so nothing may use it.
    assert not any(r["Type"].startswith("AWS::AppRunner::") for r in app["Resources"].values())


def test_service_gets_secrets_as_runtime_secrets_not_plain_variables(app):
    service = app["Resources"]["Service"]["Properties"]
    container = service["PrimaryContainer"]
    secret_names = {s["Name"] for s in container["Secrets"]}
    plain_names = {v["Name"] for v in container["Environment"]}
    assert secret_names == {"PODCLATCH_TOKEN", "DATABASE_URL"}
    assert not secret_names & plain_names
    assert container["ContainerPort"] == 8000
    assert service["HealthCheckPath"] == "/healthz"


def test_minimum_service_size_and_a_single_task(app):
    service = app["Resources"]["Service"]["Properties"]
    assert (service["Cpu"], service["Memory"]) == ("256", "512")
    # One task: the server runs its own scheduled Refresh, so a second copy would repeat it.
    assert service["ScalingTarget"] == {"MinTaskCount": 1, "MaxTaskCount": 1}


def test_only_the_database_is_public(bootstrap, app):
    for template in (bootstrap, app):
        for name, resource in template["Resources"].items():
            if "PubliclyAccessible" in resource.get("Properties", {}):
                assert resource["Type"] == "AWS::RDS::DBInstance", name
    for env in ("dev", "prod"):
        rendered = render(app, {"Environment": env})
        dbs = resources_of(rendered, "AWS::RDS::DBInstance")
        assert len(dbs) == 1
        assert next(iter(dbs.values()))["Properties"]["PubliclyAccessible"] is True


def test_tls_is_forced_and_storage_encrypted(app):
    group = app["Resources"]["DatabaseParameterGroup"]["Properties"]
    assert group["Parameters"]["rds.force_ssl"] == "1"
    for db in resources_of(app, "AWS::RDS::DBInstance").values():
        props = db["Properties"]
        assert props["DBParameterGroupName"] == {"Ref": "DatabaseParameterGroup"}
        assert props["StorageEncrypted"] is True
        assert props["DBInstanceClass"] == "db.t4g.micro"
        assert props["MultiAZ"] is False
        assert props["AllocatedStorage"] == "20"


def test_https_only(app):
    out = app["Outputs"]["ServiceUrl"]["Value"]
    assert str(out).startswith("{'Fn::Sub': 'https://")
    assert not any(re.search(r"http://(?!127\.|localhost)", s) for s in strings(app))


def test_dev_and_prod_differ_only_as_decided(app):
    dev = render(app, {"Environment": "dev"})["Resources"]
    prod = render(app, {"Environment": "prod"})["Resources"]
    assert set(dev) - set(prod) == {"DatabaseDev"}
    assert set(prod) - set(dev) == {"DatabaseProd"}
    assert dev["DatabaseDev"]["DeletionPolicy"] == "Delete"
    assert dev["DatabaseDev"]["Properties"]["DeletionProtection"] is False
    assert dev["DatabaseDev"]["Properties"]["BackupRetentionPeriod"] == 1
    assert prod["DatabaseProd"]["DeletionPolicy"] == "Snapshot"
    assert prod["DatabaseProd"]["Properties"]["DeletionProtection"] is True
    assert prod["DatabaseProd"]["Properties"]["BackupRetentionPeriod"] == 7
    # The two database definitions must otherwise be identical, so the environments cannot drift.
    a = dict(dev["DatabaseDev"]["Properties"])
    b = dict(prod["DatabaseProd"]["Properties"])
    for key in ("DBInstanceIdentifier", "DeletionProtection", "BackupRetentionPeriod"):
        a.pop(key)
        b.pop(key)
    assert a == b


def test_app_parameters(app):
    params = app["Parameters"]
    assert params["Environment"]["AllowedValues"] == ["dev", "prod"]
    assert params["ImageTag"]["Default"] == ""  # empty means the environment name
    assert params["EcrRepositoryName"]["Default"] == "podclatch-server"
    assert params["RefreshIntervalHours"]["Default"] == 3
    for param in params.values():
        assert "NoEcho" not in param


def test_app_outputs_and_names(app):
    assert {"ServiceUrl", "ServiceArn", "TokenSecretArn"} <= set(app["Outputs"])
    assert app["Resources"]["Service"]["Properties"]["ServiceName"] == {
        "Fn::Sub": "podclatch-${Environment}"
    }


def test_execution_role_reads_only_the_two_runtime_secrets(app):
    role = app["Resources"]["ExecutionRole"]["Properties"]
    assert role["AssumeRolePolicyDocument"]["Statement"][0]["Principal"] == {
        "Service": "ecs-tasks.amazonaws.com"
    }
    statements = role["Policies"][0]["PolicyDocument"]["Statement"]
    assert len(statements) == 1
    assert statements[0]["Action"] == "secretsmanager:GetSecretValue"
    assert statements[0]["Resource"] == [{"Ref": "TokenSecret"}, {"Ref": "DatabaseUrlSecret"}]


def test_express_role_names_match_what_the_deploy_role_may_pass(bootstrap, app):
    """The bootstrap stack allows iam:PassRole on exactly these two name patterns."""
    names = {
        key: app["Resources"][key]["Properties"]["RoleName"]["Fn::Sub"]
        for key in ("ExecutionRole", "InfrastructureRole")
    }
    assert names == {
        "ExecutionRole": "podclatch-${Environment}-ecs-execution",
        "InfrastructureRole": "podclatch-${Environment}-ecs-infrastructure",
    }
    statement = next(s for s in _role_statements(bootstrap) if s["Sid"] == "PassTheTwoExpressRolesToEcs")
    patterns = [r["Fn::Sub"].split("role/")[1] for r in statement["Resource"]]
    assert patterns == ["podclatch-*-ecs-execution", "podclatch-*-ecs-infrastructure"]
    for name in names.values():
        assert any(re.fullmatch(p.replace("*", "(dev|prod)"), name.replace("${Environment}", "dev")) for p in patterns)


def test_bootstrap_outputs_and_names(bootstrap):
    assert {"DeployRoleArn", "EcrRepositoryUri", "OidcProviderArn"} <= set(bootstrap["Outputs"])
    repo = bootstrap["Resources"]["EcrRepository"]["Properties"]
    assert repo["RepositoryName"] == "podclatch-server"
    assert repo["ImageScanningConfiguration"]["ScanOnPush"] is True
    assert "imageCountMoreThan" in repo["LifecyclePolicy"]["LifecyclePolicyText"]
    assert set(bootstrap["Resources"]) == {"OidcProvider", "EcrRepository", "DeployRole"}


def _role_statements(bootstrap):
    role = bootstrap["Resources"]["DeployRole"]["Properties"]
    return role["Policies"][0]["PolicyDocument"]["Statement"]


def _actions(statement):
    actions = statement["Action"]
    return [actions] if isinstance(actions, str) else actions


def test_deploy_role_is_limited_to_ecr_and_ecs_express_updates(bootstrap):
    statements = _role_statements(bootstrap)
    assert len(bootstrap["Resources"]["DeployRole"]["Properties"]["Policies"]) == 1
    assert "ManagedPolicyArns" not in bootstrap["Resources"]["DeployRole"]["Properties"]
    all_actions = [a for s in statements for a in _actions(s)]
    for action in all_actions:
        assert "*" not in action
        assert action.split(":")[0] in {"ecr", "ecs", "iam"}, action
        assert not action.startswith(("cloudformation:", "rds:", "apprunner:"))
    # The only IAM permission is passing the two Express roles to ECS.
    assert {a for a in all_actions if a.startswith("iam:")} == {"iam:PassRole"}
    # The ECS write is limited to updating a service, never creating or deleting one.
    assert {a for a in all_actions if a.startswith("ecs:") and not a.startswith(("ecs:Describe", "ecs:List"))} == {
        "ecs:UpdateExpressGatewayService",
        "ecs:RegisterTaskDefinition",
    }
    wildcard_resources = {a for s in statements if s["Resource"] == "*" for a in _actions(s)}
    assert wildcard_resources == {
        "ecr:GetAuthorizationToken",
        "ecs:RegisterTaskDefinition",
        "ecs:DescribeTaskDefinition",
    }
    ecs_services = next(s for s in statements if s["Sid"] == "UpdateAndWatchExpressServices")
    assert ecs_services["Resource"]["Fn::Sub"].endswith(":service/default/podclatch-*")
    passrole = next(s for s in statements if s["Sid"] == "PassTheTwoExpressRolesToEcs")
    assert passrole["Condition"]["StringEquals"]["iam:PassedToService"] == [
        "ecs.amazonaws.com",
        "ecs-tasks.amazonaws.com",
    ]
    ecr_statement = next(s for s in statements if s["Sid"] == "PushPullAndRetagOneRepository")
    assert ecr_statement["Resource"] == {"Fn::GetAtt": ["EcrRepository", "Arn"]}
    for needed in ("ecr:PutImage", "ecr:BatchGetImage", "ecr:UploadLayerPart"):
        assert needed in _actions(ecr_statement)
    for denied in ("ecr:DeleteRepository", "ecr:BatchDeleteImage", "ecr:SetRepositoryPolicy"):
        assert denied not in all_actions


def test_deploy_role_trust_names_this_repository_only(bootstrap):
    template = bootstrap
    assert defaults(template)["GitHubRepository"] == GITHUB_REPOSITORY
    pattern = template["Parameters"]["GitHubRepository"]["AllowedPattern"]
    assert "*" not in pattern
    trust = template["Resources"]["DeployRole"]["Properties"]["AssumeRolePolicyDocument"]
    statement = trust["Statement"][0]
    assert statement["Action"] == "sts:AssumeRoleWithWebIdentity"
    assert "StringLike" not in statement["Condition"]  # no wildcard matching at all
    equals = statement["Condition"]["StringEquals"]
    assert equals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    subs = [s["Fn::Sub"] for s in equals["token.actions.githubusercontent.com:sub"]]
    rendered = [s.replace("${GitHubRepository}", GITHUB_REPOSITORY) for s in subs]
    assert rendered == [
        f"repo:{GITHUB_REPOSITORY}:environment:dev",
        f"repo:{GITHUB_REPOSITORY}:environment:production",
    ]
    assert all("*" not in s for s in rendered)


def test_oidc_provider_is_optional(bootstrap):
    provider = bootstrap["Resources"]["OidcProvider"]
    assert provider["Condition"] == "CreateProvider"
    assert provider["Properties"]["Url"] == "https://token.actions.githubusercontent.com"
    assert provider["Properties"]["ClientIdList"] == ["sts.amazonaws.com"]
    skipped = render(bootstrap, {"CreateOidcProvider": "false"})
    assert "OidcProvider" not in skipped["Resources"]
