@echo off
echo Pushing ReelGuard to https://github.com/D3V1LxD/ReelGuard.git ...
git branch -M main
git remote set-url origin https://github.com/D3V1LxD/ReelGuard.git 2>nul || git remote add origin https://github.com/D3V1LxD/ReelGuard.git
git push -u origin main
echo Done! Open https://github.com/D3V1LxD/ReelGuard/actions to download your compiled APK.
pause
