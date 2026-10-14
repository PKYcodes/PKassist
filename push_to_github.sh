#!/usr/bin/env bash
set -e

echo "Pushing PKassist v2.0 Pro to PKYcodes/PKassist.git ..."
git init
git branch -M main
git add .
git commit -m "Upgrade to PKassist v2.0 Pro: Floating Popup, Multi-Step Hinglish NLU, WhatsApp/Alarm & Signed Release"

if git remote | grep -q origin; then
  git remote set-url origin https://github.com/PKYcodes/PKassist.git
else
  git remote add origin https://github.com/PKYcodes/PKassist.git
fi

git push -u origin main --force
echo "Pushed! GitHub Actions is now building the signed PKassist Pro v2.0.x APK."
