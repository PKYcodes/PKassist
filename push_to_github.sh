#!/usr/bin/env bash
set -e

echo "Pushing PKassist v3.0 Ultra (Gemini Glass & Voice Unlock Edition) to PKYcodes/PKassist.git ..."
git init
git branch -M main
git add .
git commit -m "Upgrade to PKassist v3.0 Ultra: Gemini Glass Bottom Popup, 24/7 Lock-Screen Mic & Voice PIN Unlock"

if git remote | grep -q origin; then
  git remote set-url origin https://github.com/PKYcodes/PKassist.git
else
  git remote add origin https://github.com/PKYcodes/PKassist.git
fi

git push -u origin main --force
echo "Pushed! GitHub Actions is now building PKassist Ultra v3.0.x APK."
