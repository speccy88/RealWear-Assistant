#!/usr/bin/env python3
"""Print a small device report. Never reads application data or API secrets."""
import argparse
import subprocess
from import_key import find_adb

def main():
    parser = argparse.ArgumentParser(description="Diagnostic RealWear sans collecte de secrets.")
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    args = parser.parse_args()
    adb = [find_adb(args.adb)] + (["-s", args.serial] if args.serial else [])
    commands = {
        "Modèle": ["shell", "getprop", "ro.product.model"],
        "Fabricant": ["shell", "getprop", "ro.product.manufacturer"],
        "Android": ["shell", "getprop", "ro.build.version.release"],
        "API Android": ["shell", "getprop", "ro.build.version.sdk"],
        "Architecture": ["shell", "getprop", "ro.product.cpu.abi"],
        "Résolution": ["shell", "wm", "size"],
        "Densité": ["shell", "wm", "density"],
        "Heure Android": ["shell", "date"],
        "Application installée": ["shell", "pm", "path", "com.fred.realweargpt"],
    }
    for title, command in commands.items():
        result = subprocess.run(adb + command, capture_output=True, text=True, timeout=15)
        print(title + ": " + (result.stdout.strip() if result.returncode == 0 else "indisponible"))

if __name__ == "__main__":
    main()
