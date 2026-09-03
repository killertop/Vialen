#!/usr/bin/env python3
"""
Deterministic Synthetic Subscription Generator for Vialen Benchmarks.
Generates 100, 1000, 5000, 10000 node datasets + malformed corpus.
Uses fixed random seed = 42.
Contains NO real server IP addresses, passwords, or tokens.
"""

import base64
import os
import random
import sys
import urllib.parse

def generate_node(i, rng):
    protocols = ["ss", "vmess", "vless", "trojan", "hy2", "tuic", "socks5", "http"]
    proto = protocols[i % len(protocols)]

    cities = ["HongKong-🇭🇰", "Tokyo-🇯🇵", "Singapore-🇸🇬", "LosAngeles-🇺🇸", "London-🇬🇧", "Frankfurt-🇩🇪", "Seoul-🇰🇷", "Taipei-🇹🇼"]
    city = rng.choice(cities)

    # Intentionally insert duplicate names occasionally
    if i > 0 and rng.random() < 0.05:
        name = f"Vialen-FastRoute-{city}-01"
    else:
        name = f"Vialen-{proto.upper()}-{city}-{i:05d}"

    host = f"node-{i:05d}.vialen-synthetic.internal"
    port = 10000 + (i % 50000)
    user_id = f"00000000-0000-0000-0000-{i:012d}"
    secret = f"pass-{i:06d}"

    if proto == "ss":
        cipher = rng.choice(["chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm"])
        userinfo = base64.urlsafe_b64encode(f"{cipher}:{secret}".encode()).decode().rstrip("=")
        uri = f"ss://{userinfo}@{host}:{port}#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "ss",
            "server": host,
            "port": port,
            "cipher": cipher,
            "password": secret
        }
    elif proto == "vmess":
        v_json = f'{{"v":"2","ps":"{name}","add":"{host}","port":"{port}","id":"{user_id}","aid":"0","scy":"auto","net":"ws","type":"none","host":"{host}","path":"/ws","tls":"tls","sni":"{host}"}}'
        b64_json = base64.b64encode(v_json.encode()).decode()
        uri = f"vmess://{b64_json}"
        clash_entry = {
            "name": name,
            "type": "vmess",
            "server": host,
            "port": port,
            "uuid": user_id,
            "alterId": 0,
            "cipher": "auto",
            "tls": True,
            "network": "ws",
            "ws-opts": {"path": "/ws", "headers": {"Host": host}}
        }
    elif proto == "vless":
        uri = f"vless://{user_id}@{host}:{port}?encryption=none&security=reality&sni=yahoo.com&pbk=dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "vless",
            "server": host,
            "port": port,
            "uuid": user_id,
            "cipher": "none",
            "tls": True,
            "flow": "xtls-rprx-vision",
            "reality-opts": {"public-key": "dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9", "short-id": "0123456789abcdef"}
        }
    elif proto == "trojan":
        uri = f"trojan://{secret}@{host}:{port}?sni={host}&type=tcp#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "trojan",
            "server": host,
            "port": port,
            "password": secret,
            "sni": host
        }
    elif proto == "hy2":
        uri = f"hy2://{secret}@{host}:{port}?sni={host}&obfs=salamander&obfs-password=obfs-{i:04d}#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "hysteria2",
            "server": host,
            "port": port,
            "password": secret,
            "sni": host,
            "obfs": "salamander",
            "obfs-password": f"obfs-{i:04d}"
        }
    elif proto == "tuic":
        uri = f"tuic://{user_id}:{secret}@{host}:{port}?congestion_control=bbr&alpn=h3&sni={host}#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "tuic",
            "server": host,
            "port": port,
            "uuid": user_id,
            "password": secret,
            "congestion-controller": "bbr",
            "sni": host
        }
    elif proto == "socks5":
        uri = f"socks5://user{i}:{secret}@{host}:{port}#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "socks5",
            "server": host,
            "port": port,
            "username": f"user{i}",
            "password": secret
        }
    else: # http
        uri = f"https://admin{i}:{secret}@{host}:{port}#{urllib.parse.quote(name)}"
        clash_entry = {
            "name": name,
            "type": "http",
            "server": host,
            "port": port,
            "username": f"admin{i}",
            "password": secret,
            "tls": True
        }

    return uri, clash_entry

def generate_clash_yaml(entries):
    lines = ["port: 7890", "socks-port: 7891", "mode: rule", "log-level: info", "proxies:"]
    for p in entries:
        lines.append(f"  - name: \"{p['name']}\"")
        lines.append(f"    type: {p['type']}")
        lines.append(f"    server: {p['server']}")
        lines.append(f"    port: {p['port']}")
        for k, v in p.items():
            if k in ["name", "type", "server", "port"]:
                continue
            if isinstance(v, bool):
                lines.append(f"    {k}: {'true' if v else 'false'}")
            elif isinstance(v, dict):
                lines.append(f"    {k}:")
                for subk, subv in v.items():
                    if isinstance(subv, dict):
                        lines.append(f"      {subk}:")
                        for subk2, subv2 in subv.items():
                            lines.append(f"        {subk2}: \"{subv2}\"")
                    else:
                        lines.append(f"      {subk}: \"{subv}\"")
            else:
                lines.append(f"    {k}: \"{v}\"")
    return "\n".join(lines) + "\n"

def main():
    base_dir = "qa/fixtures/subscriptions"
    os.makedirs(base_dir, exist_ok=True)
    malformed_dir = os.path.join(base_dir, "malformed")
    os.makedirs(malformed_dir, exist_ok=True)

    scales = [100, 1000, 5000, 10000]

    for count in scales:
        rng = random.Random(42 + count)
        uris = []
        clash_entries = []
        for i in range(count):
            uri, clash = generate_node(i, rng)
            uris.append(uri)
            clash_entries.append(clash)

        plain_text = "\n".join(uris) + "\n"
        b64_text = base64.b64encode(plain_text.encode()).decode() + "\n"
        clash_yaml = generate_clash_yaml(clash_entries)

        with open(os.path.join(base_dir, f"sub_{count}_nodes.txt"), "w", encoding="utf-8") as f:
            f.write(plain_text)
        with open(os.path.join(base_dir, f"sub_{count}_nodes.b64"), "w", encoding="utf-8") as f:
            f.write(b64_text)
        with open(os.path.join(base_dir, f"clash_{count}_nodes.yaml"), "w", encoding="utf-8") as f:
            f.write(clash_yaml)

        print(f"Generated scale {count}: txt ({len(plain_text)} B), b64 ({len(b64_text)} B), yaml ({len(clash_yaml)} B)")

    # Malformed Corpus
    with open(os.path.join(malformed_dir, "broken_base64.txt"), "w") as f:
        f.write("ss://!@#$$%^&*()___broken_base64_content\n")

    with open(os.path.join(malformed_dir, "truncated_uri.txt"), "w") as f:
        f.write("vmess://eyJ2IjoiMiIsInBzIjoiVHJ1bmNhdGVk\n")

    with open(os.path.join(malformed_dir, "invalid_percent_encoding.txt"), "w") as f:
        f.write("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@1.1.1.1:8388#Invalid%ZZ%Tag\n")

    with open(os.path.join(malformed_dir, "duplicate_yaml_keys.yaml"), "w") as f:
        f.write("proxies:\n  - name: node1\n    type: ss\n    server: 1.1.1.1\n    port: 8388\n    cipher: aes-128-gcm\n    password: p\n  - name: node1\n    type: ss\n    server: 1.1.1.1\n    port: 8388\n    cipher: aes-128-gcm\n    password: p\n")

    with open(os.path.join(malformed_dir, "malformed_yaml.yaml"), "w") as f:
        f.write("proxies:\n  - name: node1\n  type: ss\n  [invalid: indentation\n")

    with open(os.path.join(malformed_dir, "huge_scalar.yaml"), "w") as f:
        f.write("proxies:\n  - name: \"" + "A" * 100000 + "\"\n    type: ss\n    server: 1.1.1.1\n    port: 8388\n    cipher: aes-128-gcm\n    password: p\n")

    with open(os.path.join(malformed_dir, "extreme_name_length.txt"), "w") as f:
        f.write("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@1.1.1.1:8388#" + "LongName" * 2000 + "\n")

    with open(os.path.join(malformed_dir, "unsupported_protocol.txt"), "w") as f:
        f.write("unknownproto://user:pass@1.1.1.1:8080#Unsupported\n")

    with open(os.path.join(malformed_dir, "hysteria1_faketcp_wechat.txt"), "w") as f:
        f.write("hysteria://1.1.1.1:443?protocol=faketcp&auth=123#FakeTcp\nhysteria://1.1.1.1:443?protocol=wechat-video&auth=123#WeChat\n")

    print("Malformed corpus generated.")

if __name__ == "__main__":
    main()
