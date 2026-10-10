"""Creates the experiments' tenant through the API: an owner, a store, a product with deep stock.
Writes deploy/state/<env>/chaos.json (gitignored: host, ids, the owner's generated credentials) for run.sh and upload.py.
  python3 deploy/chaos/setup.py --env staging"""
import argparse, json, re, secrets, ssl, time, urllib.parse, urllib.request, uuid

p = argparse.ArgumentParser(); p.add_argument("--env", default="staging"); a = p.parse_args()
env = dict(line.strip().split("=", 1) for line in open(f"deploy/env/{a.env}.env") if "=" in line and not line.startswith("#"))
base = f"https://{env['DOMAIN']}:{env['HTTPS_PORT']}"
tls = ssl.create_default_context(cafile=f"deploy/state/{a.env}/root.crt")
mailpit = f"http://127.0.0.1:{env['OPS_PORT_PREFIX']}8025"

def call(method, url, body=None, headers=None):
    r = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                               headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(r, context=tls) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None

email, password = f"chaos-{uuid.uuid4().hex[:8]}@chaos.test", secrets.token_urlsafe(18)
call("POST", base + "/auth/v1/registrations", {"email": email, "password": password})
for _ in range(60):
    found = json.load(urllib.request.urlopen(f"{mailpit}/api/v1/search?query=" + urllib.parse.quote("to:" + email)))["messages"]
    if found:
        break
    time.sleep(0.5)
text = json.load(urllib.request.urlopen(f"{mailpit}/api/v1/message/{found[0]['ID']}"))["Text"]
call("POST", base + "/auth/v1/email-verifications/confirmation", {"token": re.search(r"token=([A-Za-z0-9_-]+)", text).group(1)})
auth = {"Authorization": "Bearer " + call("POST", base + "/auth/v1/token", {"email": email, "password": password})["access_token"]}
handle = "chaos-" + uuid.uuid4().hex[:6]
store = call("POST", base + "/admin/v1/stores", {"handle": handle, "name": "Chaos", "currency": "USD"}, auth)["id"]
product = call("POST", base + f"/admin/v1/stores/{store}/products", {"title": "Chaos Lamp", "handle": "chaos-lamp", "status": "ACTIVE",
               "options": [], "variants": [{"price": "10.00", "options": []}]}, auth)
variant = product["variants"][0]["id"]
call("POST", base + f"/admin/v1/stores/{store}/variants/{variant}/inventory/adjustments", {"delta": 1000000, "reason": "RECEIVED"}, auth)
out = {"host": f"{handle}.{env['DOMAIN']}", "port": int(env["HTTPS_PORT"]), "storeId": store, "variantId": variant, "ownerEmail": email, "ownerPassword": password}
json.dump(out, open(f"deploy/state/{a.env}/chaos.json", "w"))
print(json.dumps({k: v for k, v in out.items() if k != "ownerPassword"}))
