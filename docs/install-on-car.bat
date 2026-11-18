@echo off
chcp 65001 >nul
cd /d "%~dp0"
set P=com.zmd.carhub
echo ==== تنصيب ZMD Car على سيارة BYD ====
echo بالسيارة: الإعدادات - خيارات المطور - تصحيح الأخطاء اللاسلكي - اقتران بجهاز باستخدام رمز
echo.
set /p PAIR=اكتب عنوان الاقتران مثل 192.168.0.164:37389 : 
set /p CODE=اكتب رمز الاقتران (6 ارقام) : 
adb pair %PAIR% %CODE%
echo.
set /p CONN=اكتب العنوان اللي بالصفحة الرئيسية لتصحيح الأخطاء اللاسلكي مثل 192.168.0.164:42001 : 
adb connect %CONN%
adb devices
echo.
echo ---- التنصيب ----
for %%F in (ZMD-Car-*.apk) do set APK=%%F
adb install -r "%APK%"
if errorlevel 1 (
  echo فشل التنصيب فوق النسخة القديمة، راح امسحها وانصّب من جديد
  adb uninstall %P%
  adb install "%APK%"
)
echo ---- الصلاحيات ----
adb shell pm grant %P% android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant %P% android.permission.ACCESS_FINE_LOCATION
adb shell pm grant %P% android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant %P% android.permission.BLUETOOTH_CONNECT
adb shell pm grant %P% android.permission.POST_NOTIFICATIONS
adb shell appops set %P% SYSTEM_ALERT_WINDOW allow
adb shell appops set %P% REQUEST_INSTALL_PACKAGES allow
adb shell appops set %P% RUN_IN_BACKGROUND allow
adb shell appops set %P% RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +%P%
adb shell settings put secure enabled_accessibility_services %P%/%P%.HubA11yService
adb shell settings put secure accessibility_enabled 1
adb shell cmd package set-home-activity %P%/.HomeActivity
adb shell monkey -p %P% -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo ==== خلص. ZMD Car منصّب وكل الصلاحيات مفعّلة ====
pause
