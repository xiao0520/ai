"""验证公开假凭证的签名测试向量；不连接服务，不实现生产鉴权。"""

import hashlib
import hmac
import json
from pathlib import Path


def main() -> None:
    """核对原始正文摘要、规范字节和签名，并检查关键字段变更。"""
    fixture = json.loads(
        Path(__file__).with_name("internal-signature-vectors.json").read_text("utf-8")
    )
    for item in fixture["vectors"]:
        digest = hashlib.sha256(item["rawBody"].encode("utf-8")).hexdigest()
        canonical = "\n".join(
            [
                "2",
                item["method"],
                item["path"],
                item["timestamp"],
                item["nonce"],
                digest,
            ]
        )
        key = item["testSigningKey"].encode("utf-8")
        sign = hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        assert digest == item["expectedBodySha256"], item["name"]
        assert canonical == item["canonical"], item["name"]
        assert sign == item["expectedSignature"], item["name"]
        for changed in (
            canonical.replace("\nPOST\n", "\nGET\n", 1),
            canonical.replace(item["path"], item["path"] + "/other", 1),
            canonical[:-64]
            + hashlib.sha256((item["rawBody"] + " ").encode()).hexdigest(),
        ):
            assert hmac.new(key, changed.encode(), hashlib.sha256).hexdigest() != sign
    print(
        f"{len(fixture['vectors'])} internal signature vectors verified; endpoint binding pending"
    )


if __name__ == "__main__":
    main()
