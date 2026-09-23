#!/usr/bin/env python3
# Post-purge verification trigger
import re
import subprocess
import sys
from collections import defaultdict

PATTERNS = {
    "private-key-header": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github-token": re.compile(r"(?:ghp_|github_pat_)[A-Za-z0-9_]{20,}"),
    "openai-key": re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    "google-api-key": re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
    "aws-access-key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "slack-token": re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
    "stripe-live-key": re.compile(r"(?:sk|pk)_live_[A-Za-z0-9]{16,}"),
    "bearer-token": re.compile(r"(?i)authorization\s*[:=]\s*bearer\s+[A-Za-z0-9._~+/-]{16,}"),
    "credential-assignment": re.compile(
        r"(?i)(?:api[_-]?key|api[_-]?token|access[_-]?token|client[_-]?secret|"
        r"storePassword|keyPassword|password)\s*[:=]\s*['\"][^'\"]{6,}['\"]"
    ),
}

BINARY_SECRET_EXTENSIONS = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")
IGNORE_PREFIXES = (".git/", "build/", "app/build/")
IGNORE_PATHS = {"gradle/wrapper/gradle-wrapper.jar"}

def run(*args, text=True):
    return subprocess.check_output(args, text=text, stderr=subprocess.DEVNULL)

def main():
    commits = run("git", "rev-list", "--all").splitlines()
    findings = defaultdict(set)

    for sha in commits:
        try:
            names = run("git", "ls-tree", "-r", "--name-only", sha).splitlines()
        except subprocess.CalledProcessError:
            continue

        for path in names:
            if path in IGNORE_PATHS or path.startswith(IGNORE_PREFIXES):
                continue
            lower = path.lower()
            if lower.endswith(BINARY_SECRET_EXTENSIONS):
                findings["sensitive-file"].add((sha, path))
                continue

            try:
                blob = subprocess.check_output(
                    ["git", "show", f"{sha}:{path}"],
                    stderr=subprocess.DEVNULL,
                )
            except subprocess.CalledProcessError:
                continue

            if len(blob) > 1024 * 1024 or b"\x00" in blob:
                continue
            try:
                content = blob.decode("utf-8", "strict")
            except UnicodeDecodeError:
                continue

            for label, pattern in PATTERNS.items():
                if pattern.search(content):
                    findings[label].add((sha, path))

    print("QuietLink full-history credential audit")
    print("Secret values are intentionally NOT printed.")
    if not findings:
        print("RESULT: no credential-pattern findings")
        return 0

    total = 0
    for label in sorted(findings):
        items = sorted(findings[label])
        total += len(items)
        unique_paths = sorted({p for _, p in items})
        print(f"\n[{label}] occurrences={len(items)} unique_paths={len(unique_paths)}")
        for path in unique_paths:
            commits_for_path = sorted({sha[:12] for sha, p in items if p == path})
            preview = ",".join(commits_for_path[:8])
            suffix = "..." if len(commits_for_path) > 8 else ""
            print(f"  {path} :: {preview}{suffix}")

    print(f"\nRESULT: {total} commit/path findings")
    return 2

if __name__ == "__main__":
    sys.exit(main())
