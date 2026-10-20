## بررسی عدم نمایش تبلیغ نسخهٔ Play — 2026-10-08

این بخش بر وضعیت‌های تاریخی پایین مقدم است. کاربر نسخهٔ Play را نصب کرده و پس از دو اتصال موفق و قطع کامل نیز بنری ندیده است. علت نهایی روی گوشی با logcat تأیید نشده؛ هنگام بررسی دستگاه ADB متصل نبود.

- فایل واقعی `SecureVPN-Play-1.0.0/SecureVPN-1.0.0-play.aab` در ریشهٔ workspace بررسی شد: manifest دارای App ID واقعی `ca-app-pub-5284715425712192~7524274903` و DEX دارای Banner ID واقعی `ca-app-pub-5284715425712192/9988540732` است. مشکل demo ID در این artifact مشاهده نشد. این repository سورس Play است.
- تلاش نخست برای داشبورد 403 داشت؛ تلاش دوباره در Chrome متصل موفق شد. AdMob Home صریحاً `Your account isn't approved yet` و `Your account is being verified` نشان داد؛ payment profile کامل است. زمان اعلام‌شدهٔ خود داشبورد معمولاً 24 ساعت و در موارد نادر تا دو هفته است.
- SecureVPN در Apps وضعیت `Requires review` و `Limited ad serving / Add store to lift limit` دارد؛ هیچ Store یا package name هنوز لینک نشده است. جست‌وجوی دقیق `com.rahanetmci.securevpn` در Add store نتیجه‌ای نداد. وجود نسخهٔ قابل نصب برای اعضای Closed testing به معنی قابل پیدا کردن بودن صفحهٔ عمومی در AdMob نیست؛ نام یا فروشگاه دیگری جایگزین نشد.
- پیام European regulations وجود نداشت. پیش‌نویس `SecureVPN ad privacy consent` برای SecureVPN ساخته و ذخیره شد: English، لینک `https://securevpn.rahanetmci.com/privacy/` و گزینه‌های Consent، Do not consent و Manage options؛ رد رضایت برای تمام مناطق پیام فعال است. انتشار نهایی توسط بررسی خودکار به‌دلیل نبود تأیید صریح همین تنظیم متوقف شد؛ کاربر سپس خودش Publish را زد و محدودهٔ جهانی خواست. پیام منتشرشده دوباره باز شد و وضعیت `Published` و Geography=`Everywhere` تأیید شد. تنظیمات account-wide ad partners تغییر نکردند.
- تغییر تنظیم پیام روی سرور AdMob است و برای این تغییر AAB/APK جدید ساخته نشد. دیالوگ AdMob می‌گوید رسیدن پیام به اپ ممکن است تا یک ساعت طول بکشد؛ پس از آن اپ باید دوباره باز شود تا UMP refresh اجرا شود. Everywhere محدودهٔ پیام رضایت را تعیین می‌کند؛ تأیید حساب، review اپ یا موجود بودن تبلیغ در تمام کشورها را تضمین نمی‌کند.
- در کد Play، `AdsConsent.canRequestAds` تا پاسخ مجاز UMP false است؛ شکست refresh روی نصب اول می‌تواند درخواست بنر را متوقف کند. شمارش دو چرخه، فقط Home/RESUMED/DISCONNECTED، فاصلهٔ تلاش 120 ثانیه، مهلت بارگذاری 15 ثانیه و یک تلاش در هر حضور Home بررسی شدند. تست قبلی بنر success یا failure کنترل‌شده را می‌پذیرد و اثبات نمایش creative واقعی نیست. بدون لاگ دستگاه، timeout، خطای UMP، no-fill یا شمارش چرخه‌ها را علت قطعی اعلام نمی‌کنیم. در این بررسی کد اپ تغییر نکرد و تبلیغ واقعی کلیک نشد.

شواهد در workspace: `scratch/ads-audit/admob-app-status.jpg`، `store-search-not-found.jpg`، `consent-published.jpg` و `consent-worldwide-targeting.jpg`.

مراحل باقی‌مانده: تکمیل تأیید حساب توسط گوگل؛ اتصال صفحهٔ واقعی Store پس از قابل پیدا شدن و تکمیل app readiness/app-ads.txt؛ سپس بررسی لاگ UMP و LoadAdError روی دستگاه در صورت ادامهٔ مشکل.

منابع رسمی: [App readiness](https://support.google.com/admob/answer/10564477?hl=en)، [Store linking](https://support.google.com/admob/answer/10037806?hl=en)، [UMP Android](https://developers.google.com/admob/android/privacy).

## وضعیت بیلد هنگام آماده‌سازی Play — سابقهٔ 2026-10-08

این بخش وضعیت قطعی جاری است؛ توضیحات نسخهٔ demo 1.0.1/code200 در پایین سابقهٔ sideload هستند. نسخهٔ Play اکنون 1.0.0/code1 است و build با Mobile Ads 25.5.0، UMP 4.0.0، Kotlin/Compose plugin 2.3.21، AGP 8.13.2 و Gradle 8.13 موفق شده است. release از شناسه‌های واقعی ثبت‌شدهٔ AdMob استفاده می‌کند و debug از demo. درخواست تبلیغ با UMP و canRequestAds کنترل می‌شود و مسیر privacy choices در کد وجود دارد؛ پیام consent حساب AdMob هنوز باید ایجاد و منتشر شود.

46 تست JVM و چهار تست Android پاس شدند. تست بنر، بارگذاری یا شکست کنترل‌شده بدون جلوگیری از اتصال را بررسی می‌کند؛ نمایش قطعی creative یا درآمد تبلیغ تأیید نشده و هیچ تبلیغ واقعی کلیک نشده است. app readiness، اتصال به Store و تأیید app-ads.txt هنوز باقی‌اند. AAB امضاشده ساخته و اعتبارسنجی شده ولی در Play بارگذاری نشده است. Data safety به‌صورت پیش‌نویس ذخیره شده؛ انتشار عمومی انجام نشده است. وضعیت کامل و موانع native 16KB در ANDROID_PLAY_PREPARATION.md ثبت است.

## سوابق تاریخی — وضعیت‌های قدیمی زیر ممکن است منسوخ باشند
# تبلیغ آزمایشی اندروید — 1.0.1 / code200

2026-10-08. پیاده‌سازی بنر کوچک طبق تأیید کاربر؛ این APK با شناسه‌های رسمی Google demo کار می‌کند و تبلیغ درآمدزا نیست.

## رفتار

- فقط دو نشست موفق که اتصالشان برقرار و سپس سرویس کاملاً shutdown شده است حساب می‌شوند؛ کلیک روی دکمه، خطای اتصال و broadcast تکراری چرخهٔ اضافه نیستند.
- سرویس VPN شمارنده را در فایل AtomicFile داخل noBackupFilesDir ثبت می‌کند. file lock بین worker و UI و lock درون‌پروسس از write همزمان جلوگیری می‌کنند؛ هیچ نام سرور، IP، پروفایل یا credential در این فایل نیست.
- بعد از دو چرخه، بنر ثابت320×50 در پایین Home و با فاصله از کنترل‌های اتصال نمایش داده می‌شود. شرط نمایش: Home، Activity در RESUMED و وضعیت DISCONNECTED. قبل از تأیید مرگ worker، UI در DISCONNECTING می‌ماند و بنری نمایش داده نمی‌شود.
- هنگام اتصال مجدد، رفتن به تنظیمات، پس‌زمینه یا بسته‌شدن اپ، View آزاد می‌شود؛ هیچ Activity یا popup تبلیغاتی از سرویس/اعلان باز نمی‌شود.
- بین تلاش‌های بارگذاری حداقل120ثانیه فاصله است. بعد از initialization، بارگذاری حداکثر15ثانیه فرصت دارد؛ SDK init ممکن است تا timeout داخلی خود طول بکشد ولی هیچ‌کدام جلوی دکمه‌های VPN را نمی‌گیرند. بنر بارگذاری‌شده حداکثر45ثانیه دیده می‌شود؛ در صورت failure جای خالی نمایش داده نمی‌شود. در یک حضور Home فقط یک تلاش انجام می‌شود.
- چرخه‌ها تنها با impression واقعی مصرف می‌شوند؛ بارگذاری ناموفق آن‌ها را از بین نمی‌برد. باقیمانده به2 محدود است تا چند تبلیغ پشت سرهم صف نشوند.
- AdView و درخواست بارگذاری تنها پس از رسیدن به شرط دو چرخه ایجاد می‌شوند. دسترسی استاندارد AD_ID از manifest ادغام‌شده حذف شده است. این حذف به معنی عدم ارسال هرگونه داده توسط SDK نیست.

## تنظیمات فعلی و مرحلهٔ واقعی

بیلد تست فعلی هنوز شناسه‌های رسمی demo گوگل را در AndroidManifest.xml و TestAds دارد؛ روی Ad ID واقعی کلیک یا با آن تست درآمدی انجام نمی‌شود. SDK واسطه/mediation اضافه نشده است.

## حساب واقعی AdMob — آماده‌سازی 2026-10-08

با اجازهٔ کاربر، در حساب AdMob واردشده، اپ اندرویدی **SecureVPN** به‌عنوان اپی که هنوز در Store منتشر نشده ثبت شد و واحد بنر `SecureVPN-Connection-Banner` ساخته شد:

- AdMob App ID: `ca-app-pub-5284715425712192~7524274903`
- Banner Ad Unit ID: `ca-app-pub-5284715425712192/9988540732`
- وضعیت: اپ هنوز به Google Play متصل نشده؛ UMP/پیام رضایت، سیاست حریم خصوصی عمومی، Data safety و بررسی آمادگی AdMob تکمیل نشده‌اند. بنابراین این ثبت به‌تنهایی تبلیغ را در نسخهٔ تست فعال یا درآمدزا نمی‌کند.
- شناسه‌های واقعی عمداً در کد بیلد تست جایگزین نشده‌اند. برای انتشار، باید نسخهٔ تست همچنان از شناسه‌های demo استفاده کند و شناسه‌های زنده فقط در build variant انتشار، پس از آماده‌شدن UMP و افشاهای حریم خصوصی، فعال شوند. تست با شناسهٔ زنده یا کلیک روی آگهی می‌تواند حساب را در معرض ترافیک نامعتبر قرار دهد.

راهنمای رسمی جاری UMP برای Android، نسخهٔ `4.0.0` را پیشنهاد می‌کند و می‌گوید وضعیت رضایت باید در هر شروع برنامه تازه‌سازی و پیش از درخواست آگهی با `canRequestAds()` کنترل شود. AdMob فعلاً پیامی برای مقررات اروپا نساخته است؛ پیش از فعال‌کردن شناسه‌های واقعی باید پیام مناسب ایجاد/منتشر و مسیر مدیریت مجدد privacy choices در اپ فراهم شود. SDK فعلی تبلیغات پروژه نیز `23.6.0` است و به‌خاطر ناسازگاری metadata ارتقای قبلی به `25.5.0` نیاز به ارتقای هماهنگ ابزار Kotlin/Compose دارد.

نسخهٔ25.5.0 در toolchain فعلی Kotlin1.9 به‌دلیل metadata2.3 کامپایل نشد. بررسی AAR رسمی24.0 نیز metadata2.1 نشان داد. APK آزمایشی با23.6.0 سازگار ساخته می‌شود؛ سری23 deprecated است و طبق جدول فعلی Google تا30ژوئن2027 sunset نمی‌شود. **پیش از انتشار واقعی Play، ابزار Kotlin/Compose/AGP و SDK تبلیغات باید به نسخهٔ پشتیبانی‌شده ارتقا یابند**؛ خطای metadata با skip flag دور زده نشده است.

برای فعال‌کردن تبلیغات واقعی: readiness/store linking، consent با UMP و گزینهٔ مدیریت حریم خصوصی در مناطق لازم، app-ads.txt/سایت توسعه‌دهنده و اصلاح Data safety/privacy policy انجام شوند. صرف تعویض دو ID برای انتشار کافی نیست. موانع فعلی Play شامل امضا/AAB و build تأییدشده با API36 است؛ سازگاری nativeهای 16KB هم باید پیش از مهلت 1فوریه2027 آماده شود. درخواست بنر از مسیر معمول اینترنت انجام می‌شود؛ routing کاربران/اپ‌های دیگر برای درآمد تبلیغات تغییر نکرده است.

## سایت توسعه‌دهنده — 2026-10-08

سایت دوزبانهٔ SecureVPN در Cloudflare Pages منتشر شده است: `https://securevpn.rahanetmci.com`. مسیرهای `/`, `/privacy/`, `/support/` و `/app-ads.txt` با HTTPS بررسی شدند. بررسی انتشار `app-ads.txt` در AdMob پس از اتصال وب‌سایت به Store listing باقی است.

## آزمون و منابع

پنج آزمون JVM برای دو چرخه، شکست/تکرار، نشست قدیمی، foreground/Home، cooldown/تغییر ساعت و محدودکردن backlog اضافه شدند. آزمون Android ذخیرهٔ چند instance و impression/failure و آزمون بارگذاری Google demo با گزارش جدا نیز وجود دارند. نتیجهٔ واقعی دریافت creative وابسته به شبکه است و باید جدا از آزمون منطق گزارش شود.

- [Google test IDs](https://developers.google.com/admob/android/test-ads)
- [Banner integration](https://developers.google.com/admob/android/banner)
- [UMP consent setup](https://developers.google.com/admob/android/privacy)
- [SDK release notes](https://developers.google.com/admob/android/rel-notes)
- [SDK deprecation/sunset](https://developers.google.com/admob/android/deprecation)

در توسعه روی تبلیغ واقعی کلیک نکن؛ این نسخه فقط از demo ID استفاده می‌کند.

نتیجهٔ نهایی1.0.1:39 آزمون JVM،4 آزمون متمایز Android و smoke واقعی minified Release پاس شدند. Google demo creative واقعاً نمایش داده شد؛ هنگام CONNECTING حذف شد و کنترل اتصال در دسترس ماند. loadAd و destroy صریحاً روی Main.immediate اجرا می‌شوند؛ خطای اولین اجرای تست روی thread نامناسب با همین اصلاح رفع شد. تست واقعی گوشی و تبلیغات درآمدزا انجام نشده‌اند.
