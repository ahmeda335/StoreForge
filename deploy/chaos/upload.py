"""Walks one product-image upload as the chaos store's owner and prints each step's status (P17, storage-down).
  python3 deploy/chaos/upload.py --env staging"""
import argparse, json, ssl, time, urllib.error, urllib.request

p = argparse.ArgumentParser(); p.add_argument("--env", default="staging"); a = p.parse_args()
env = dict(line.strip().split("=", 1) for line in open(f"deploy/env/{a.env}.env") if "=" in line and not line.startswith("#"))
base = f"https://{env['DOMAIN']}:{env['HTTPS_PORT']}"
st = json.load(open(f"deploy/state/{a.env}/chaos.json"))
tls = ssl.create_default_context(cafile=f"deploy/state/{a.env}/root.crt")

def call(method, url, body=None, headers=None, raw=None):
    r = urllib.request.Request(url, method=method, data=raw if raw is not None else (None if body is None else json.dumps(body).encode()),
                               headers={"Content-Type": "image/png" if raw is not None else "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(r, context=tls, timeout=20) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)
    except OSError as e:
        return "ERR", str(e).encode(), {}

token = json.loads(call("POST", base + "/auth/v1/token", {"email": st["ownerEmail"], "password": st["ownerPassword"]})[1])["access_token"]
auth = {"Authorization": "Bearer " + token}
product = json.loads(call("GET", base + f"/admin/v1/stores/{st['storeId']}/products?limit=1", headers=auth)[1])["data"][0]["id"]
status, body, _ = call("POST", base + f"/admin/v1/stores/{st['storeId']}/products/{product}/images/uploads", {"contentType": "image/png", "sizeBytes": 64}, auth)
print("start upload:", status)
upload = json.loads(body)
print("PUT to storage:", call("PUT", upload["upload"]["url"], raw=b"\x89PNG\r\n\x1a\n" + bytes(56))[0])
started = time.time()
status, body, headers = call("POST", base + f"/admin/v1/stores/{st['storeId']}/products/{product}/images/{upload['imageId']}/completion", {}, auth)
code = json.loads(body).get("code") if body.startswith(b"{") else None
print(f"complete upload: {status} {code} Retry-After={headers.get('Retry-After')} in {time.time() - started:.1f}s")
print("storefront product:", call("GET", f"https://{st['host']}:{env['HTTPS_PORT']}/storefront/v1/products/chaos-lamp")[0])
print("create cart:", call("POST", f"https://{st['host']}:{env['HTTPS_PORT']}/storefront/v1/carts")[0])
