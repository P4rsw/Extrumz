# گزارش جامع بازبینی فنی پروژه Extrumz (اکسترامز)

> **تاریخ بازبینی:** ۲۰ اکتبر ۲۰۲۶  
> **محدوده بازبینی:** ساختار پروژه، تنظیمات CI، مستندات، مدیریت ریلیز و اسکریپت‌ها، هماهنگی رشته‌ها و بومی‌سازی، سرویس backend، و ناسازگاری‌های احتمالی برند و اصالت فورک.  
> **توجه مهم:** بر اساس بریف پروژه، در هیچ‌یک از فایل‌های زیر تغییری اعمال نشده و موارد ذکرشده صرفاً برای تصمیم‌گیری و اصلاحات آتی گزارش می‌شوند.

---

## ⚠️ ۱) هشدارهای فوری و ریسک‌های بحرانی (شکست در انتشار و بیلد)

### ۱.۱. باگ بحرانی تگ‌گذاری نسخه در `release.sh`
- **محل:** [`release.sh:78`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/release.sh#L78) و [`release.sh:136`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/release.sh#L136)
- **شرح مشکل:** متغیر `CURRENT_NAME` در خط ۷۸ قبل از اعمال تغییرات نسخه با `sed`، از `app/build.gradle.kts` استخراج می‌شود. زمانی که کاربر اسکریپت را با آرگومان نسخه جدید اجرا می‌کند (مثلاً `./release.sh --version 1.9`)، فایل گریدل به درستی تغییر می‌کند، اما در خط ۱۳۶ در راهنمای انتشار همچنان مقدار قدیمی چاپ می‌شود:
  ```bash
  git tag v$CURRENT_NAME && git push origin v$CURRENT_NAME
  ```
- **چرا مهم است:** اسکریپت به کاربر پیشنهاد می‌دهد تگ نسخه قبلی را بزند (مثلاً `v1.8`). اگر تگ قبلاً وجود داشته باشد گیت خطا می‌دهد، و اگر وجود نداشته باشد، باینری نسخه ۱.۹ با تگ ۱.۸ منتشر می‌شود!
- **پیشنهاد اصلاح:** پس از تغییر نسخه، اگر `VERSION` خالی نبود، مقدار `CURRENT_NAME="$VERSION"` شود یا متغیر جدیدی مانند `TARGET_VERSION="${VERSION:-$CURRENT_NAME}"` برای پیام پایانی استفاده شود.

### ۱.۲. هاردکد بودن مسیر JDK در اسکریپت انتشار
- **محل:** [`release.sh:100`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/release.sh#L100)
- **شرح مشکل:** خط ۱۰۰ به صورت هاردکد دستور زیر را اجرا می‌کند:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-temurin $GRADLE :app:assembleProdRelease --console=plain
  ```
- **چرا مهم است:** اگر این اسکریپت روی توزیع دیگری از لینوکس (مانند Arch با مسیر `java-21-openjdk` یا فدورا) یا مک‌او‌اس یا دستگاهی که Temurin در مسیر متفاوتی است اجرا شود، با خطای دایرکتوری نامعتبر فوراً کرش می‌کند، حتی اگر متغیر `JAVA_HOME` سیستم کاربر به درستی تنظیم شده باشد.
- **پیشنهاد اصلاح:** بررسی مقدار فعلی `JAVA_HOME`؛ تنها در صورتی که خالی باشد یا جاوا ۲۱ نباشد، مسیر پیش‌فرض Temurin تست شود:
  ```bash
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-temurin}"
  ```

---

## 🔍 ۲) سرویس `backend/` و معماری Listen Together

### ۲.۱. نام‌گذاری و شرح کاملاً اشتباه در مستندات قبلی
- **محل:** `README.md:107`، `README.fa.md:104` (در نسخه قبلی) و متن بریف کاری.
- **شرح مشکل:** در مستندات قبلی نوشته شده بود:
  `backend/ the lyrics backend that LyricsPlus queries, in Go`  
  در حالی که بررسی کامل کد نشان می‌دهد این پوشه **هیچ ارتباطی با لیریکس ندارد**. این سرویس، سرور هم‌شنوی گروهی (**Listen Together / Party Server**) بر بستر WebSocket و زبان Go است که روی پلتفرم Render (`render.yaml`) دیپلوی می‌شود. استعلام لیریکس در اپ مستقیماً به سرورهای خارجی مانند LyricsPlus (`lyricsplus.bitchord.org`)، LRCLIB و... فرستاده می‌شود.
- **پیشنهاد اصلاح:** شرح در `README.md` و `README.fa.md` اصلاح شد.

### ۲.۲. وضعیت فیچر Listen Together در اپ اندروید (پوشه مرده / Orphaned)
- **محل:** ریشه پوشه [`backend/`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/backend) و [`ADDITIONAL.md:6`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/ADDITIONAL.md#L6)
- **شرح مشکل:** قابلیت «Listen Together / Party» در کامیت‌های قبلی به طور کامل از کد کاتلین اپلیکیشن اندروید حذف شده است. اکنون هیچ کلاس یا لایه‌ای در `app/` به وب‌سوکت یا اندپوینت‌های این سرور وصل نمی‌شود. در نتیجه کل دایرکتوری `backend/` در حال حاضر کد بدون استفاده در اپلیکیشن نهایی است.
- **چرا مهم است:** حفظ و نگهداری این سرور Go و کانفیگ `render.yaml` باعث سردرگمی توسعه‌دهنده می‌شود، مگر اینکه تصمیم به بازگردانی یا اتصال مجدد این قابلیت در آپدیت‌های آینده گرفته شود.
- **پیشنهاد:** مشخص کردن وضعیت فیچر: اگر قرار است در آینده پیاده‌سازی شود، در مستندات تحت عنوان Planned/Roadmap قید شود؛ اگر کاملاً کنار گذاشته شده، پوشه `backend/` می‌تواند آرشیو گردد.

### ۲.۳. لینک دانلود نامعتبر (404) در صفحه فرود سرور Go
- **محل:** [`backend/main.go:636`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/backend/main.go#L636)
- **شرح مشکل:** در صفحه HTML مربوط به جوین شدن به پارتی، لینک زیر قرار دارد:
  ```html
  Don't have Extrumz yet? <a href="https://github.com/kushagrasinghx/Extrumz/releases" target="_blank" rel="noopener">Download it here</a>.
  ```
  این آدرس وجود خارجی ندارد (۴۰۴) و ترکیب نادرستی از نام کاربری سازنده پروژه مبدأ و نام پروژه فعلی است.
- **پیشنهاد اصلاح:** جایگزینی با آدرس واقعی مخزن یا حذف لینک در صورت خصوصی بودن مخزن.

---

## 📁 ۳) ساختار پروژه و نام‌گذاری‌ها (Project Structure)

### ۳.۱. عدم تطابق ساختار دایرکتوری فایل‌ها با پکیج کاتلین
- **محل:** تمام فایل‌های کاتلین در مسیرهای زیر:
  - `app/src/main/java/com/music/extrumz/...`
  - `app/src/test/java/com/music/extrumz/...`
  - `app/src/androidTest/java/com/music/extrumz/...`
- **شرح مشکل:** نام پوشه روی دیسک `com/music/extrumz` است، اما اعلان بسته (package declaration) در ابتدای تقریباً تمام فایل‌ها `package com.extrumz.app.*` است (که مطابق با `namespace = "com.extrumz.app"` در گریدل است).
- **چرا مهم است:** کامپایلر کاتلین این ساختار را قبول می‌کند، اما در قراردادهای اندروید و ابزارهای توسعه (مانند Android Studio، بازسازی پکیج، Lint و رندر ساختار درختی پروژه)، این عدم تطابق به شدت نامتعارف است و مانع ریفکتور تمیز می‌شود.
- **پیشنهاد اصلاح:** انتقال دایرکتوری‌ها به مسیر متناظر با پکیج:
  `app/src/main/java/com/extrumz/app/...`

### ۳.۲. نام گمراه‌کننده فایل دیتابیس (`TeleMusicDatabase.kt`)
- **محل:** [`app/src/main/java/com/music/extrumz/data/db/TeleMusicDatabase.kt:34`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/app/src/main/java/com/music/extrumz/data/db/TeleMusicDatabase.kt#L34)
- **شرح مشکل:** داخل فایل، کلاس به نام درست `ExtrumzDatabase` تعریف شده است، اما نام خود فایل کماکان `TeleMusicDatabase.kt` باقی مانده است. این موضوع در تضاد با یادداشت بریف است که «TeleMusic در هیچ کجای کد وجود ندارد».
- **پیشنهاد اصلاح:** تغییر نام فایل به `ExtrumzDatabase.kt`.

---

## ⚙️ ۴) بررسی CI و گیت‌هاب اکشنز (`.github/workflows/android.yml`)

### ۴.۱. عدم اجرای تست‌های واحد در CI (فاجعهٔ خاموش)
- **محل:** [`.github/workflows/android.yml`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/.github/workflows/android.yml)
- **شرح مشکل:** ورک‌فلوهای تعریف شده فقط دستورهای `assembleDevDebug` و `assembleProdRelease` را اجرا می‌کنند. **هیچ مرحله‌ای برای اجرای `./gradlew test` یا تست‌های واحد وجود ندارد!**
- **چرا مهم است:** اگر کدی کامیت شود که تست‌های رگرسیون، پروکسی یا استریم را بشکند، تا زمانی که پروژه کامپایل شود، علامت تیک سبز CI نشان داده خواهد شد. ۶۳۱ تست موجود پروژه در CI عملاً خاک می‌خورند.
- **پیشنهاد اصلاح:** افزودن گام اجرای تست‌ها پیش از ساخت APK در جاب PRها:
  ```yaml
  - name: Run Unit Tests
    run: ./gradlew :app:testProdDebugUnitTest
  ```

### ۴.۲. وابستگی شکننده به سکرت‌های Base64 در انتشار Prod
- **محل:** [`.github/workflows/android.yml:58-63`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/.github/workflows/android.yml#L58-L63)
- **شرح مشکل:**
  ```bash
  echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > extrumz-release.jks
  echo "storeFile=extrumz-release.jks" > keystore.properties
  ```
  اگر سکرت `KEYSTORE_BASE64` در ریپازیتوری تنظیم نشده باشد، دستور `base64 -d` یک فایل خالی با حجم صفر بایت می‌سازد. در این حالت در `app/build.gradle.kts` خط ۱۳۵ شرط `store.exists()` مقدار `true` برمی‌گرداند (چون فایل با حجم صفر وجود دارد!) و فرایند بیلد به جای ساخت نسخه unsigned، با خطای غیرمنتظره و مبهم در مرحله اعتبارسنجی امضا شکست می‌خورد.
- **پیشنهاد اصلاح:** بررسی شرطی حجم فایل یا وجود مقدار سکرت پیش از نوشتن `keystore.properties`.

---

## 📄 ۵) بررسی مستندات پروژه

### ۵.۱. ناهمخوانی‌های متعدد در `CONTRIBUTING.md`
- **محل:** [`CONTRIBUTING.md`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/CONTRIBUTING.md)
- **شرح موارد جا مانده از پروژه مبدأ (BitChord):**
  1. **خط ۱۲:** اشاره به برنچ‌های ریلیز BitChord:
     `during the v1.6.x release cycle, contributions branch from v1.6.1...` در حالی که Extrumz مستقیماً روی `main` کار می‌کند و سیستم برنچینگ مستقل دارد.
  2. **خط ۲۴:** توصیه به JDK 17، در حالی که در `README.md` و اسکریپت انتشار JDK 21 الزامی است.
  3. **خط ۲۵:** مقدار `compileSdk = 36` ذکر شده، در حالی که در `app/build.gradle.kts` به ۳۷ ارتقا یافته است.
  4. **خطوط ۳۴ و ۴۴:** تنها دستورات بیلد `assembleDevDebug` ذکر شده، در حالی که طعم بیلد اصلی نسخه پروداکشن `prod` است.
- **پیشنهاد اصلاح:** به‌روزرسانی مقادیر به JDK 21، SDK 37، و همگام‌سازی توضیحات برنچ‌ها با روند فعلی توسعه.

---

## 🌐 ۶) لینک‌های مرده و ناسازگاری‌های برند در داخل کد (Brand & Link Inconsistencies)

### ۶.۱. لینک‌های خراب (404) در صفحه تنظیمات اپلیکیشن
- **محل:** [`app/src/main/java/com/music/extrumz/ui/screens/SettingsSheet.kt:1109-1117`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/app/src/main/java/com/music/extrumz/ui/screens/SettingsSheet.kt#L1109-L1117)
- **شرح مشکل:** در بخش انتهای تنظیمات (کارت About):
  ```kotlin
  withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/Extrumz", linkStyles)) { append("GitHub") }
  withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) { append("Developer") }
  withLink(LinkAnnotation.Url("https://extrumz.kushagrasingh.in/", linkStyles)) { append("Website") }
  ```
  هر دو لینک گیت‌هاب و وب‌سایت در این بخش خراب (404) هستند. این لینک‌ها نتیجه جایگزینی مکانیکی `BitChord` با `Extrumz` در آدرس‌های سازنده اصلی بوده‌اند.
- **پیشنهاد اصلاح:** تغییر آدرس GitHub به ریپازیتوری فعلی (`https://github.com/P4rsw/Extrumz`) و حذف یا تصحیح لینک Website.

### ۶.۲. کامنت‌های قدیمی و گمراه‌کننده در فایل‌های بیلد
1. **[`settings.gradle.kts:13`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/settings.gradle.kts#L13):**
   `// NewPipeExtractor is published via JitPack.`  
   کتابخانه NewPipeExtractor دیگر در پروژه نیست؛ JitPack برای دریافت لایبرری کلاینت تلگرام (`com.github.tdlibx:td`) استفاده می‌شود.
2. **[`app/build.gradle.kts:273`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/app/build.gradle.kts#L273):**
   `// ---- QR encoding, for the party invite ----`  
   کتابخانه `zxing:core` در حال حاضر برای ورود با کد QR به تلگرام (`QrLoginScreen.kt`) استفاده می‌شود، نه اینوایت پارتی!
3. **[`gradle.properties:26-28`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/gradle.properties#L26-L28):**
   کامنت و متغیر `android.suppressUnsupportedCompileSdk=36` به نسخه AGP 8.2.1 اشاره دارد؛ در حالی که نسخه AGP اکنون به 8.10.1 و `compileSdk` به 37 ارتقا یافته است.

---

## 🔤 ۷) وضعیت متن‌ها و بومی‌سازی (Strings & Localization)

### ۷.۱. وضعیت هم‌ترازی فایل‌های منابع
- **فایل‌های بررسی‌شده:**
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-fa/strings.xml`
- **نتیجه:** وضعیت هماهنگی فوق‌العاده است. دقیقاً **۷۰۵ کلید متنی** و **۱۹ کلید حالت جمع (Plurals)** در هر دو فایل وجود دارد و هیچ کلیدی جا نمانده است.

### ۷.۲. متن هاردکدشده در کد کاتلین (به جای Resource)
- **محل:** [`app/src/main/java/com/music/extrumz/ui/screens/ProfileScreen.kt:559`](file:///run/media/parsa/4EE0F882E0F87197/Agents/Hermes/Extrumz/app/src/main/java/com/music/extrumz/ui/screens/ProfileScreen.kt#L559)
- **شرح مشکل:** عبارت زیر به صورت مستقیم در کد قرار گرفته است:
  ```kotlin
  text = if (isRtlReading) "در حال اسکن تمام کانال‌ها…" else "Scanning all channels…",
  ```
- **پیشنهاد اصلاح:** انتقال این دو رشته به `strings.xml` و خواندن آن با `stringResource()`.

---

## 🛡 ۸) وضعیت محرمانگی و سکرت‌ها

- **بررسی کی‌استور و پراپرتی‌ها:**
  فایل‌های `extrumz-release.jks`، `keystore.properties` و `local.properties` در `.gitignore` ثبت شده‌اند و در تاریخچه گیت هرگز کامیت نشده‌اند.
- **توصیه امنیتی:** این فایل‌ها در فضای کاری محلی حضور دارند؛ در زمان گرفتن بکاپ، اشتراک‌گذاری یا استفاده از اسکریپت‌های آرشیو (نظیر دستور `tar` ذکر شده در بخش ۱ بریف)، اطمینان حاصل شود که این فایل‌ها استخراج یا نشت پیدا نکنند.

---

## ❓ ۹) مواردی که امکان آزمایش یا ارزیابی آن‌ها وجود نداشت

1. **تست فیزیکی اجرای باینری نهایی با R8:** به دلیل عدم دسترسی به دستگاه فیزیکی متصل به محیط حاضر، رفتار ران‌تایم کلاس‌های بازتابی پس از شرینک کامل R8 در بیلد Release به صورت مستقیم مشاهده نشد (هرچند قوانین keep در ProGuard تکمیل هستند).
2. **بررسی سکرت‌های سرور در GitHub Actions:** امکان استعلام آنلاین سکرت‌های ریپازیتوری خصوصی وجود ندارد و فرض بر تطابق نام‌ها در ریپازیتوری است.
3. **تست ارتباط واقعی TDLib با پراکسی زنده:** ارتباط مستقیم سوکت به دیتاسنترهای تلگرام در این محیط به شبکه زنده تلگرام متصل نشد.
