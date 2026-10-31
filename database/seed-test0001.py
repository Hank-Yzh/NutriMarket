#!/usr/bin/env python3
"""Seed test0001/test0001 with validated meals for testing Agents 2-5.

Requires Python 3.9+, PyYAML, mysql, and htpasswd with BCrypt support.
Reads the application's local database configuration without printing credentials.
Preview: python3 database/seed-test0001.py
Execute: python3 database/seed-test0001.py --execute
Month: python3 database/seed-test0001.py --days 30 --execute
The default period is the previous completed Monday-Sunday in Australia/Sydney.
With --days, the default period ends yesterday. Existing accounts are preserved.
"""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from urllib.parse import urlparse
from uuid import NAMESPACE_URL, uuid5
from zoneinfo import ZoneInfo

import yaml


ROOT = Path(__file__).resolve().parents[1]
USERNAME = PASSWORD = "test0001"
MEMBER_ID = str(uuid5(NAMESPACE_URL, "nutrimarket/test-fixtures/test0001"))
ZONE = ZoneInfo("Australia/Sydney")
REQUIRED_UNITS = {"1008": "kcal", "1003": "g", "1004": "g", "1005": "g"}


def resolve(value):
    def replace(match):
        name, default = match.groups()
        if name in os.environ:
            return os.environ[name]
        if default is None:
            raise ValueError(f"Missing environment variable: {name}")
        return default

    return re.sub(r"\$\{([^}:]+)(?::([^}]*))?\}", replace, str(value))


class Database:
    def __init__(self):
        settings = yaml.safe_load((ROOT / "config/api-keys.yml").read_text()) or {}
        database = settings.get("spring", {}).get("datasource", {})

        def setting(name, alias, default):
            return resolve(os.environ.get("SPRING_DATASOURCE_" + name.upper(),
                           database.get(name, os.environ.get(alias, default))))

        url = urlparse(setting("url", "MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/nutrimarket").removeprefix("jdbc:"))
        if url.scheme != "mysql" or not url.hostname or not url.path.strip("/"):
            raise ValueError("Expected a JDBC MySQL URL with a database name.")
        mysql = shutil.which("mysql")
        if not mysql:
            raise ValueError("The mysql command-line client is required.")
        self.command = [mysql, "--protocol=TCP", "--host", url.hostname,
                        "--port", str(url.port or 3306), "--user",
                        setting("username", "MYSQL_USERNAME", "nutrimarket"),
                        "--database", url.path.strip("/"), "--default-character-set=utf8mb4",
                        "--batch", "--raw", "--skip-column-names", "--connect-timeout=5"]
        self.environment = os.environ.copy()
        self.environment["MYSQL_PWD"] = setting("password", "MYSQL_PASSWORD", "")
        self.label = f"{url.hostname}:{url.port or 3306}/{url.path.strip('/')}"

    def run(self, sql):
        result = subprocess.run(self.command, input="SET time_zone='+00:00';\n" + sql,
                                text=True, capture_output=True, env=self.environment, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or "MySQL command failed.")
        return result.stdout.strip()


def quote(value):
    # Hex literals avoid SQL-mode-dependent escaping, including JSON backslashes.
    return "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4)"


def dump(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def iso(value):
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def bcrypt_hash():
    helper = shutil.which("htpasswd")
    if not helper:
        raise ValueError("htpasswd with BCrypt support is required.")
    result = subprocess.run([helper, "-niBC", "12", USERNAME], input=PASSWORD + "\n",
                            text=True, capture_output=True, check=True)
    encoded = result.stdout.strip().split(":", 1)[1]
    if not encoded.startswith(("$2a$12$", "$2b$12$", "$2y$12$")):
        raise ValueError("htpasswd did not produce the expected BCrypt hash.")
    return encoded


def source_meals():
    """Read synthetic USDA-backed fixtures from the repository, not another account."""
    sql = (ROOT / "src/main/resources/demo-data.sql").read_text()
    account = re.search(r"VALUES \('([^']+)', 'demo_live',", sql)
    if not account:
        raise ValueError("The repository demo_live fixture account is missing.")
    escapes = {"0": "\0", "n": "\n", "r": "\r", "t": "\t", "b": "\b", "Z": "\x1a"}
    source = []
    for line in sql.splitlines():
        if not line.startswith("INSERT IGNORE INTO `dietary_record` "):
            continue
        for literal in re.findall(r"'((?:\\.|[^'\\])*)'", line):
            if not literal.startswith("{"):
                continue
            document = re.sub(r"\\(.)", lambda match: escapes.get(match[1], match[1]), literal)
            record = json.loads(document)
            if record.get("memberId") == account[1] and record.get("status") == "VALIDATED":
                source.append(record)
    return source


def prepare(start, days, now, member_id, version):
    source = source_meals()
    source_days = Counter(instant(r["mealTime"]).astimezone(ZONE).date() for r in source)
    if len(source) != 21 or len(source_days) != 7 or set(source_days.values()) != {3}:
        raise ValueError("Repository fixtures must contain seven days with three validated meals per day.")
    first = min(source_days)
    if first.weekday() != 0 or max(source_days) != first + timedelta(days=6):
        raise ValueError("Repository fixtures must cover a complete Monday-Sunday week.")

    rows = []
    by_weekday = {day: sorted((r for r in source if instant(r["mealTime"]).astimezone(ZONE).weekday() == day),
                             key=lambda r: r["mealTime"]) for day in range(7)}
    for offset in range(days):
        meal_day = start + timedelta(days=offset)
        for original in by_weekday[meal_day.weekday()]:
            record = copy.deepcopy(original)
            local = instant(record["mealTime"]).astimezone(ZONE)
            meal_time = datetime.combine(meal_day, local.time(), ZONE)
            monday = meal_day - timedelta(days=meal_day.weekday())
            # Retain IDs from older one-week fixtures; all other accounts include their own ID.
            identity = "" if member_id == MEMBER_ID else member_id + "/"
            record_id = str(uuid5(NAMESPACE_URL, f"nutrimarket/test-fixtures/test0001/{identity}{monday}/{original['recordId']}"))
            record.update(recordId=record_id, memberId=member_id, profileVersion=version,
                          status="VALIDATED", mealTime=iso(meal_time), createdAt=iso(now),
                          analyzedAt=iso(now), confirmedAt=iso(now), expiresAt=None,
                          photoProcessingConsent=False, attempts=0, failures=[],
                          audit=[{"action": "TEST_FIXTURE_SEEDED_VALIDATED", "at": iso(now)}])
            record["provenance"] = [p for p in record.get("provenance", [])
                                    if not p["provider"].startswith("NutriMarket")]
            record["provenance"].append({"provider": "NutriMarket test fixture",
                "reference": "test0001: synthetic repository demo-data.sql nutrition; no photo analysis",
                "url": None, "retrievedAt": iso(now)})
            for nutrient_id, unit in REQUIRED_UNITS.items():
                nutrient = record["nutrients"].get(nutrient_id)
                if not nutrient or nutrient["unit"].lower() != unit or Decimal(str(nutrient["amount"])) < 0:
                    raise ValueError(f"Missing or invalid nutrient {nutrient_id} in {original['recordId']}")
                total = sum(Decimal(str(food["nutrients"][nutrient_id]["amount"])) for food in record["foodItems"])
                if abs(total - Decimal(str(nutrient["amount"]))) > Decimal("0.000001"):
                    raise ValueError("Meal totals do not match the source food items.")
            payload = {key: record[key] for key in (
                "schemaVersion", "recordId", "memberId", "profileVersion", "policyVersion",
                "mealTime", "analyzedAt", "confirmedAt", "foodItems", "nutrients", "confidence", "provenance")}
            payload["timestamp"] = iso(now)
            rows.append((record, payload))

    profile = {"memberId": member_id, "version": version, "createdAt": iso(now), "information": {
        "expectedVersion": 0, "timezone": "Australia/Sydney",
        "goals": "Test Agents 2-5 using seeded meal records and weekly nutrition assessments.",
        "allergies": [], "restrictions": [], "conditions": [],
        "preferences": "No special preferences.", "targetSource": "NutriMarket demo fixture daily targets",
        "targets": {key: {"minimum": low, "maximum": high, "unit": REQUIRED_UNITS[key]}
                    for key, low, high in [("1008", 1800, 2200), ("1003", 70, 130),
                                           ("1004", 50, 80), ("1005", 200, 300)]},
        "aiSummaryConsent": False}}
    return profile, sorted(rows, key=lambda row: row[0]["mealTime"])


def verify(database, start, days, rows, member_id, version, allow_missing=False):
    account_raw = database.run("SELECT JSON_OBJECT('id',m.id,'username',m.username,'enabled',m.enabled,"
        "'profile',JSON_EXTRACT(p.document,'$')) FROM member_account m "
        f"JOIN member_health_profile p ON p.member_id=m.id AND p.profile_version={version} "
        f"WHERE m.id={quote(member_id)};")
    account = json.loads(account_raw) if account_raw else {}
    profile = account.get("profile", {})
    if not allow_missing and not (account.get("username") == USERNAME and account.get("enabled") == 1
            and profile.get("memberId") == member_id and profile.get("version") == version
            and set(REQUIRED_UNITS).issubset(profile.get("information", {}).get("targets", {}))):
        raise ValueError("The enabled test account or its initial health profile is missing or invalid.")
    ids = ",".join(quote(record["recordId"]) for record, _ in rows)
    raw = database.run("SELECT JSON_OBJECT('record',JSON_EXTRACT(r.document,'$'),"
        "'payload',JSON_EXTRACT(o.payload,'$'),'delivered',o.delivered,"
        "'samePayload',BINARY o.payload=BINARY h.document,'status',r.status,"
        "'member',r.member_id,'intakeMember',h.member_id,'intakeProfile',h.profile_version,"
        "'mealTime',DATE_FORMAT(h.meal_time,'%Y-%m-%dT%H:%i:%s.000Z')) "
        "FROM dietary_record r LEFT JOIN dietary_outbox o ON o.record_id=r.record_id "
        "LEFT JOIN health_intake h ON h.record_id=r.record_id "
        f"WHERE r.record_id IN ({ids});")
    actual = [json.loads(line) for line in raw.splitlines() if line]
    if not allow_missing and len(actual) != days * 3:
        raise ValueError(f"Expected {days * 3} complete record/outbox/intake rows, found {len(actual)}.")
    daily_counts = Counter()
    for row in actual:
        record, payload = row["record"], row["payload"]
        if not payload:
            raise ValueError("An existing fixture is missing its outbox or intake. No records were changed.")
        if not (row["member"] == row["intakeMember"] == record["memberId"] == payload["memberId"] == member_id
                and row["status"] == record["status"] == "VALIDATED"
                and row["samePayload"] == row["delivered"] == 1
                and row["intakeProfile"] == record["profileVersion"] == payload["profileVersion"]
                and 1 <= record["profileVersion"] <= version
                and record["recordId"] == payload["recordId"]
                and record["nutrients"] == payload["nutrients"]
                and instant(row["mealTime"]) == instant(record["mealTime"]) == instant(payload["mealTime"])):
            raise ValueError("Persisted Agent 1/Agent 2 data is inconsistent.")
        daily_counts[instant(record["mealTime"]).astimezone(ZONE).date()] += 1
    expected_days = Counter(instant(record["mealTime"]).astimezone(ZONE).date() for record, _ in rows)
    if not allow_missing and daily_counts != expected_days:
        raise ValueError("Persisted records do not cover exactly three meals on each requested day.")
    if allow_missing:
        return {row["record"]["recordId"] for row in actual}
    print(f"Verified: {len(actual)} VALIDATED meals, {len(actual)} delivered outbox entries, "
          f"{len(actual)} Agent 2 intake entries; three meals on each of {len(daily_counts)} days.")


def period(today, day_count=None, start=None, week_start=None):
    days = day_count if day_count is not None else 7
    if not 1 <= days <= 366:
        raise ValueError("--days must be between 1 and 366.")
    if week_start is not None and week_start.weekday() != 0:
        raise ValueError("--week-start must be a Monday.")
    start = start or week_start or (today - timedelta(days=days) if day_count is not None
                                  else today - timedelta(days=today.weekday() + 7))
    if start + timedelta(days=days) > today or start < today - timedelta(days=730):
        raise ValueError("Choose completed days within the last two years.")
    return start, days


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Commit the seed transaction; otherwise preview only.")
    dates = parser.add_mutually_exclusive_group()
    dates.add_argument("--week-start", type=date.fromisoformat, help="Completed Monday, YYYY-MM-DD.")
    dates.add_argument("--start-date", type=date.fromisoformat, help="First completed day, YYYY-MM-DD.")
    parser.add_argument("--days", type=int, help="Number of completed days; defaults to the days ending yesterday.")
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    today = now.astimezone(ZONE).date()
    start, days = period(today, args.days, args.start_date, args.week_start)
    database = Database()
    account_raw = database.run("SELECT JSON_OBJECT('id',id,'enabled',enabled) FROM member_account "
                               f"WHERE username={quote(USERNAME)};")
    account = json.loads(account_raw) if account_raw else None
    member_id = account["id"] if account else MEMBER_ID
    if account and account["enabled"] != 1:
        raise ValueError("The existing test account is disabled. No account was changed.")
    existing_profile = database.run("SELECT document FROM member_health_profile "
        f"WHERE member_id={quote(member_id)} ORDER BY profile_version DESC LIMIT 1;") if account else ""
    current = json.loads(existing_profile) if existing_profile else None
    if current and current["information"]["timezone"] != ZONE.key:
        raise ValueError("The existing profile uses a different timezone. No profile was changed.")
    version = current["version"] if current else 1
    profile, rows = prepare(start, days, now, member_id, version)
    print(f"Database: {database.label}")
    print(f"Account: {USERNAME}; member ID: {member_id}")
    print(f"Period: {start} to {start + timedelta(days=days-1)} (Australia/Sydney)")
    present = verify(database, start, days, rows, member_id, version, allow_missing=True) if account else set()
    pending = [(record, payload) for record, payload in rows if record["recordId"] not in present]
    print(f"Plan: {len(pending)} new validated meals with matching delivered outbox/intake entries; "
          f"{len(present)} existing fixtures preserved; health profile v{version}.")
    if not args.execute:
        print("Preview only. Run with --execute to commit.")
        return
    if not pending and current:
        verify(database, start, days, rows, member_id, version)
        print("Already seeded. Existing account, profile and downstream test results were preserved.")
        return

    statements = ["SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';", "START TRANSACTION;"]
    stamp = quote(now.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:23])
    if not account:
        statements.append("INSERT INTO member_account(id,username,password_hash,enabled,created_at) VALUES "
                          f"({quote(member_id)},{quote(USERNAME)},{quote(bcrypt_hash())},TRUE,{stamp});")
    statements.append(f"SELECT id FROM member_account WHERE id={quote(member_id)} FOR UPDATE;")
    if not current:
        statements.append("INSERT INTO member_health_profile(member_id,profile_version,document) VALUES "
                          f"({quote(member_id)},1,{quote(dump(profile))});")
    for record, payload in pending:
        record_id = quote(record["recordId"])
        fingerprint = hashlib.sha256(("test0001:" + record["recordId"]).encode()).hexdigest()
        payload_sql = quote(dump(payload))
        meal_stamp = quote(instant(record["mealTime"]).strftime("%Y-%m-%d %H:%M:%S.%f")[:23])
        statements.append("INSERT INTO dietary_record(record_id,member_id,fingerprint,fingerprint_member_id,"
            "status,created_at,expires_at,document,photo,media_type) VALUES "
            f"({record_id},{quote(member_id)},{quote(fingerprint)},{quote(member_id)},"
            f"'VALIDATED',{stamp},NULL,{quote(dump(record))},NULL,'image/jpeg');")
        statements.append("INSERT INTO dietary_outbox(record_id,payload,delivered,attempts,last_error,updated_at) "
            f"VALUES ({record_id},{payload_sql},TRUE,0,NULL,{stamp});")
        statements.append("INSERT INTO health_intake(record_id,member_id,profile_version,meal_time,document) "
            f"VALUES ({record_id},{quote(member_id)},{version},{meal_stamp},{payload_sql});")
    statements.append("COMMIT;")
    # mysql batch mode stops on the first error. A failed connection closes the
    # uncommitted transaction, rolling back every insert, including the account.
    database.run("\n".join(statements))
    verify(database, start, days, rows, member_id, version)
    mondays = [start + timedelta(days=offset) for offset in range(days)
               if (start + timedelta(days=offset)).weekday() == 0 and offset + 7 <= days]
    print("Completed assessment weeks: " + ", ".join(map(str, mondays)))
    print("Seed committed. Sign in with your existing test0001 password and generate a weekly assessment.")
    print("No assessment, meal plan, market result or order was created by this script.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"Seed failed: {error}", file=sys.stderr)
        sys.exit(1)
