#!/usr/bin/env python3
"""Import an OpenAI key without echoing it or putting it in process arguments.

ADB uses run-as on this development APK and writes only to its private directory.
SSH uses the existing Termux server; its temporary shared file is imported by the app.
No key is generated or sent to any server by this script.
"""
from __future__ import annotations
import argparse
import getpass
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

PACKAGE = "com.fred.realweargpt"
COMPONENT = PACKAGE + "/.MainActivity"
PRIVATE_WRITE = (
    "run-as " + PACKAGE + " sh -c "
    "'umask 077; mkdir -p files/import && "
    "cat > files/import/openai.key.tmp && "
    "mv files/import/openai.key.tmp files/import/openai.key'"
)
PRIVATE_IMPORTED = "run-as " + PACKAGE + " sh -c 'test -f no_backup/openai.enc && test ! -f files/import/openai.key'"

def run(command: list[str], data: bytes | None = None, timeout: int = 40) -> subprocess.CompletedProcess:
    # Commands never contain secret material. stdout/stderr are not printed on failure.
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        raise RuntimeError("La commande a échoué. Vérifie la connexion, l’autorisation et l’installation de l’APK.")
    return result

def find_adb(override: str | None) -> str:
    if override:
        return override
    found = shutil.which("adb")
    if found:
        return found
    candidates = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("ADB introuvable. Installe Android Platform Tools et ajoute son dossier au PATH, ou utilise --adb.")

def key_bytes(key_file: str | None) -> bytes:
    if key_file:
        path = Path(key_file)
        if path.stat().st_size > 2048:
            raise RuntimeError("Le fichier de clé est trop volumineux.")
        key = path.read_text(encoding="utf-8-sig").strip()
    else:
        if not sys.stdin.isatty():
            raise RuntimeError("Utilise un terminal interactif ou --key-file. La clé ne sera jamais affichée.")
        key = getpass.getpass("Clé API OpenAI (saisie masquée) : ").strip()
    if not key.startswith("sk-") or not 20 <= len(key) <= 1024 or re.search(r"\s", key):
        raise RuntimeError("Format de clé invalide. Aucun transfert effectué.")
    return (key + "\n").encode("utf-8")

def import_adb(args, data: bytes) -> None:
    adb = [find_adb(args.adb)]
    if args.serial:
        adb += ["-s", args.serial]
    run(adb + ["get-state"])
    run(adb + ["shell", "run-as", PACKAGE, "id"])
    run(adb + ["shell", "-T", PRIVATE_WRITE], data)
    run(adb + ["shell", "am", "start", "-n", COMPONENT, "--ez", "import_key", "true"])
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ["shell", "-T", PRIVATE_IMPORTED], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        if result.returncode == 0:
            print("Import confirmé : clé chiffrée sur le casque, fichier temporaire supprimé.")
            print("L’accès au modèle sera vérifié quand tu choisiras Démarrer.")
            return
        time.sleep(0.6)
    raise RuntimeError("Clé transférée, mais l’import n’est pas confirmé. Arrête la conversation, puis choisis Menu → Importer la clé.")

def import_ssh(args, data: bytes) -> None:
    if not args.host or not args.user:
        raise RuntimeError("Avec --transport ssh, indique --host et --user (le compte Termux).")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", args.host) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", args.user) or not 1 <= args.port <= 65535:
        raise RuntimeError("Nom d’hôte ou utilisateur SSH invalide.")
    ssh = shutil.which("ssh")
    if not ssh:
        raise RuntimeError("Client OpenSSH introuvable.")
    directory = "/sdcard/Android/data/" + PACKAGE + "/files/import"
    command = (
        "umask 077; mkdir -p " + directory + " && cat > " + directory + "/openai.key.tmp && "
        "mv " + directory + "/openai.key.tmp " + directory + "/openai.key && "
        "/system/bin/am start -n " + COMPONENT + " --ez import_key true"
    )
    run([ssh, "-p", str(args.port), "-o", "ConnectTimeout=15", args.user + "@" + args.host, command], data, timeout=120)
    print("Clé transférée par SSH. Vérifie « Clé importée et chiffrée » sur le casque.")
    print("Le fichier temporaire est supprimé après import. Si nécessaire : Menu → Importer la clé.")

def main() -> int:
    parser = argparse.ArgumentParser(description="Importer la clé API sur RealWear Assistant, sans la saisir sur le casque.")
    parser.add_argument("--transport", choices=("adb", "ssh"), default="adb")
    parser.add_argument("--serial", help="Numéro de série ADB s’il y a plusieurs appareils.")
    parser.add_argument("--adb", help="Chemin de adb/adb.exe.")
    parser.add_argument("--host", help="Adresse du RealWear pour SSH.")
    parser.add_argument("--user", help="Utilisateur Termux retourné par whoami.")
    parser.add_argument("--port", type=int, default=8022)
    parser.add_argument("--key-file", help="Fichier local contenant la clé, facultatif. Sinon la saisie est masquée.")
    args = parser.parse_args()
    try:
        data = key_bytes(args.key_file)
        if args.transport == "adb": import_adb(args, data)
        else: import_ssh(args, data)
        return 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        # Do not interpolate subprocess diagnostics or file contents: they could contain credentials.
        print(str(error) if isinstance(error, RuntimeError) else "Opération interrompue ou connexion indisponible. Aucune clé n’est affichée.", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
