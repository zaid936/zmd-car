# ZMD Car

برنامج للشاشات بالسيارة (شاشات أندرويد وشاشات BYD): خريطة العراق بدون إنترنت ويا الطرق وكاميرات السرعة، شريط طايف، CarPlay و Android Auto (بالكيبل واللاسلكي)، متجر للبرامج والتحديثات.

A car head-unit hub for Android screens (including BYD DiLink): offline Iraq map with routing and
speed cameras, a floating bar, CarPlay and Android Auto (cable and wireless), and an app store with
updates.

## License / الرخصة

ZMD Car contains the **ZMD Auto** Android Auto engine, which is based on
[DiAuto](https://github.com/shihabal3amri/DiAuto) / Headunit Revived and licensed under the
**GNU Affero General Public License v3.0**. ZMD Car as a whole is therefore distributed under the
same license: see [LICENSE](LICENSE). The engine's source (with ZMD's changes) is published
alongside this repository (`zmd-auto`).

ZMD Car يحتوي على محرك Android Auto (ZMD Auto) المبني على DiAuto، ورخصته AGPL-3.0، فالبرنامج كله
بنفس الرخصة: أي واحد يستلم البرنامج يحق له ياخذ الكود المصدري من هنا.

**Not included:** CarPlay is provided by DiPlay, a separate app by its own developer. It is not part
of this source and is not covered by this license. Signing keys are not included either.

**مو موجود هنا:** CarPlay يشتغل ببرنامج DiPlay، وهو برنامج منفصل مو مالتنا وما داخل بهالكود ولا بهالرخصة. ومفاتيح التوقيع مو موجودة.

## Build

- JDK 17+, Android SDK 36, Gradle wrapper (9.1).
- The Android Auto engine comes from the `zmd-auto` repository:
  `gradlew :engine:publishReleasePublicationToEngineRepository` there, then point
  `settings.gradle.kts` at its `build/engine-repo`.
- `gradlew assembleRelease` = the build people install by hand (no accessibility service);
  `gradlew assembleFull` = everything (accessibility helper and home-screen option), installed over adb.
- Release signing reads `keys/signing.properties` (not in the repository): use your own key.
