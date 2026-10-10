"""Post-deploy smoke test (P16): the critical paths of one environment, end to end THROUGH the proxy.

  python3 deploy/smoke.py --domain staging.storeforge.localhost --port 18443 --mailpit http://127.0.0.1:18025 --ca root.crt

Owner sign-up (email verification) -> store -> product -> image upload (presigned, via storage.<domain>)
-> storefront on the store's own hostname -> cart -> checkout -> order visible to the merchant.
Exits non-zero with the failing step: the deploy script rolls back on that. Only standard library."""
import argparse, json, re, ssl, sys, time, urllib.error, urllib.parse, urllib.request, uuid

p = argparse.ArgumentParser()
p.add_argument("--domain", required=True)
p.add_argument("--port", type=int, default=443)
p.add_argument("--mailpit", required=True)
p.add_argument("--ca", help="CA certificate to trust (Caddy's local root); default: system trust store")
args = p.parse_args()
tls = ssl.create_default_context(cafile=args.ca)
step = "start"


def url(host, path):
    return f"https://{host}:{args.port}{path}" if args.port != 443 else f"https://{host}{path}"


def call(method, full_url, body=None, headers=None, raw=None, expect=(200, 201, 202, 204)):
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
    request = urllib.request.Request(full_url, method=method, data=data,
                                     headers={**({"Content-Type": "application/json"} if raw is None else {}), **(headers or {})})
    try:
        with urllib.request.urlopen(request, context=tls, timeout=15) as response:
            payload = response.read()
            status, response_headers = response.status, response.headers
    except urllib.error.HTTPError as e:
        payload, status, response_headers = e.read(), e.code, e.headers
    if status not in expect:
        raise AssertionError(f"{method} {full_url} -> {status} {payload[:300]!r}")
    return status, response_headers, (json.loads(payload) if payload and payload[:1] in b"{[" else payload)


def mail_token(email):
    for _ in range(60):
        found = json.load(urllib.request.urlopen(f"{args.mailpit}/api/v1/search?query=" + urllib.parse.quote("to:" + email)))["messages"]
        if found:
            text = json.load(urllib.request.urlopen(f"{args.mailpit}/api/v1/message/{found[0]['ID']}"))["Text"]
            return re.search(r"token=([A-Za-z0-9_-]+)", text).group(1)
        time.sleep(0.5)
    raise AssertionError("no verification email for " + email)


try:
    api = args.domain
    started = time.time()
    step = "security headers"
    _, headers, _ = call("GET", url(api, "/storefront/v1/products"), expect=(404,))
    assert "default-src 'none'" in headers.get("Content-Security-Policy", ""), "CSP missing"
    step = "owner sign-up + email verification"
    email, password = f"smoke-{uuid.uuid4().hex[:10]}@smoke.test", "smoke test password 2026"
    call("POST", url(api, "/auth/v1/registrations"), {"email": email, "password": password})
    call("POST", url(api, "/auth/v1/email-verifications/confirmation"), {"token": mail_token(email)})
    token = call("POST", url(api, "/auth/v1/token"), {"email": email, "password": password})[2]["access_token"]
    owner = {"Authorization": "Bearer " + token}
    step = "store + product + stock"
    handle = "smoke-" + uuid.uuid4().hex[:8]
    store = call("POST", url(api, "/admin/v1/stores"), {"handle": handle, "name": "Smoke", "currency": "USD"}, owner)[2]["id"]
    product = call("POST", url(api, f"/admin/v1/stores/{store}/products"), {"title": "Smoke Lamp", "handle": "smoke-lamp", "status": "ACTIVE",
                   "options": [], "variants": [{"price": "12.00", "options": []}]}, owner)[2]
    variant = product["variants"][0]["id"]
    call("POST", url(api, f"/admin/v1/stores/{store}/variants/{variant}/inventory/adjustments"), {"delta": 5, "reason": "RECEIVED"}, owner)
    step = "image upload via presigned URL"
    png = b"\x89PNG\r\n\x1a\n" + bytes(56)
    upload = call("POST", url(api, f"/admin/v1/stores/{store}/products/{product['id']}/images/uploads"),
                  {"contentType": "image/png", "sizeBytes": len(png)}, owner)[2]
    call("PUT", upload["upload"]["url"], raw=png, headers={"Content-Type": "image/png"}, expect=(200,))
    call("POST", url(api, f"/admin/v1/stores/{store}/products/{product['id']}/images/{upload['imageId']}/completion"), {}, owner)
    step = "storefront on the store's hostname"
    shop = f"{handle}.{args.domain}"
    shown = call("GET", url(shop, "/storefront/v1/products/smoke-lamp"))[2]
    assert shown["images"], "image not on the storefront"
    call("GET", shown["images"][0]["url"], expect=(200,))
    step = "cart + checkout + order"
    cart = call("POST", url(shop, "/storefront/v1/carts"))[2]
    cart_auth = {"X-Cart-Token": cart["cartToken"]}
    call("POST", url(shop, f"/storefront/v1/carts/{cart['cartId']}/items"), {"variantId": variant, "quantity": 1}, cart_auth)
    checkout = call("POST", url(shop, "/storefront/v1/checkouts"), {"cartId": cart["cartId"], "email": "buyer@smoke.test",
                    "shippingAddress": {"name": "Smoke Buyer", "line1": "1 Test St", "city": "Testville", "postalCode": "1000", "country": "US"}},
                    cart_auth)[2]
    done = call("POST", url(shop, f"/storefront/v1/checkouts/{checkout['id']}/completion"), {"expectedTotal": checkout["total"]["amount"]},
                {**cart_auth, "Idempotency-Key": str(uuid.uuid4())})[2]
    order = call("GET", url(api, f"/admin/v1/stores/{store}/orders/{done['orderId']}"), headers=owner)[2]
    assert order["total"]["amount"] == "12.00", order["total"]
    print(f"SMOKE OK {args.domain} in {time.time() - started:.1f}s (store {handle}, order {order['name']})")
except Exception as e:
    print(f"SMOKE FAILED at '{step}': {e}", file=sys.stderr)
    sys.exit(1)
