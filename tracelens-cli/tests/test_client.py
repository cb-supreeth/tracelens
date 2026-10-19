import io
from urllib.parse import unquote, urlsplit

from tracelens_cli.client import TraceLensClient


def test_api_labels_survive_url_encoding_without_encoded_slashes(monkeypatch):
    requests = []

    def urlopen(request, timeout):
        requests.append(request)
        return io.BytesIO(b'{"traces": []}')

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    label = "auto:GET /api/v2/features/user-licenses/reference_counts?q=a+b%20#café"

    assert TraceLensClient().find_by_lens_id(label) == {"traces": []}

    url = urlsplit(requests[0].full_url)
    assert url.query == ""
    assert url.fragment == ""
    assert " " not in url.path
    assert "%2F" not in url.path
    assert unquote(url.path.removeprefix("/tracelens/find-by-lens-id/")) == label


def test_non_destructive_export_preserves_evict_query(monkeypatch):
    requests = []

    def urlopen(request, timeout):
        requests.append(request)
        return io.BytesIO(b'{}')

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    segment_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    TraceLensClient().export(segment_id, evict=False)

    url = urlsplit(requests[0].full_url)
    assert url.path == f"/tracelens/export/{segment_id}"
    assert url.query == "evict=false"
