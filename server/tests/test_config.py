import pytest

from podclatch_server.app import create_app
from podclatch_server.config import ConfigError, Settings, load_settings

SECRET = "s3cret-token-value"


def test_defaults():
    s = load_settings({"PODCLATCH_TOKEN": SECRET})
    assert s.database_url.startswith("sqlite:")
    assert s.refresh_interval_hours == 3
    assert s.log_level == "INFO"
    assert s.token == SECRET
    assert s.test_mode is False


def test_reads_all_variables():
    s = load_settings(
        {
            "DATABASE_URL": "postgresql://db/x",
            "PODCLATCH_TOKEN": SECRET,
            "REFRESH_INTERVAL_HOURS": "1.5",
            "LOG_LEVEL": "debug",
        }
    )
    assert s.database_url == "postgresql://db/x"
    assert s.refresh_interval_hours == 1.5
    assert s.log_level == "DEBUG"


@pytest.mark.parametrize("bad", ["abc", "0", "-2", "nan", "inf"])
def test_invalid_refresh_interval_names_variable_not_value(bad):
    with pytest.raises(ConfigError) as exc:
        load_settings({"PODCLATCH_TOKEN": SECRET, "REFRESH_INTERVAL_HOURS": bad})
    assert "REFRESH_INTERVAL_HOURS" in str(exc.value)
    assert bad not in str(exc.value)


def test_invalid_log_level_names_variable_not_value():
    with pytest.raises(ConfigError) as exc:
        load_settings({"PODCLATCH_TOKEN": SECRET, "LOG_LEVEL": "loud"})
    assert "LOG_LEVEL" in str(exc.value)
    assert "loud" not in str(exc.value)


@pytest.mark.parametrize("env", [{}, {"PODCLATCH_TOKEN": ""}])
def test_token_required_outside_test_mode(env):
    with pytest.raises(ConfigError, match="PODCLATCH_TOKEN"):
        load_settings(env)
    with pytest.raises(ConfigError, match="PODCLATCH_TOKEN"):
        create_app(Settings(token=env.get("PODCLATCH_TOKEN")))


def test_token_not_required_in_test_mode():
    assert load_settings({}, test_mode=True).token is None
    assert load_settings({"PODCLATCH_TEST_MODE": "true"}).test_mode is True
    create_app(Settings(test_mode=True))


def test_test_mode_defaults_off_and_rejects_garbage():
    assert load_settings({"PODCLATCH_TOKEN": SECRET}).test_mode is False
    with pytest.raises(ConfigError, match="PODCLATCH_TEST_MODE"):
        load_settings({"PODCLATCH_TEST_MODE": "maybe"})


def test_token_never_in_repr():
    s = load_settings({"PODCLATCH_TOKEN": SECRET})
    assert SECRET not in repr(s)
    assert SECRET not in str(s)


ITUNES_DEFAULT = "https://itunes.apple.com/search"


@pytest.mark.parametrize("env", [{}, {"ITUNES_SEARCH_URL": ""}, {"ITUNES_SEARCH_URL": "  "}])
def test_itunes_search_url_defaults_to_the_real_itunes(env):
    assert load_settings({"PODCLATCH_TOKEN": SECRET, **env}).itunes_search_url == ITUNES_DEFAULT


def test_itunes_search_url_can_be_overridden():
    env = {"PODCLATCH_TOKEN": SECRET, "ITUNES_SEARCH_URL": "http://feedhost:8080/search"}
    assert load_settings(env).itunes_search_url == "http://feedhost:8080/search"


@pytest.mark.parametrize("bad", ["ftp://canary-itunes.invalid/x", "itunes.invalid/search", "//x"])
def test_invalid_itunes_search_url_names_variable_not_value(bad):
    with pytest.raises(ConfigError) as exc:
        load_settings({"PODCLATCH_TOKEN": SECRET, "ITUNES_SEARCH_URL": bad})
    assert "ITUNES_SEARCH_URL" in str(exc.value)
    assert bad not in str(exc.value)
    assert "canary-itunes" not in str(exc.value)


def test_create_app_fails_fast_on_invalid_itunes_search_url(monkeypatch):
    monkeypatch.setenv("PODCLATCH_TOKEN", SECRET)
    monkeypatch.setenv("ITUNES_SEARCH_URL", "ftp://canary-itunes.invalid/x")
    with pytest.raises(ConfigError, match="ITUNES_SEARCH_URL") as exc:
        create_app()
    assert "canary-itunes" not in str(exc.value)
