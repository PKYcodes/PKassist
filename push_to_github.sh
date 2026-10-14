#!/usr/bin/env bash
set -e

echo "Initializing PKassist Git repository..."
git init
git branch -M main
git add .
git commit -m "Initial release of PKassist AI Assistant (powered by RV GROUP)"

if git remote | grep -q origin; then
  git remote set-url origin https://github.com/PKYcodes/PKassist.git
else
  git remote add origin https://github.com/PKYcodes/PKassist.git
fi

echo "Pushing to https://github.com/PKYcodes/PKassist.git ..."
git push -u origin main
echo "Done! GitHub Actions will now auto-increment the version, build the APK, and publish a GitHub Release."
