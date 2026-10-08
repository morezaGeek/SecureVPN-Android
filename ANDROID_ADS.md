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

شناسهٔ demo app در AndroidManifest.xml و شناسهٔ fixed banner در TestAds هستند. مقدارها عمومی و رسمی Google هستند؛ App ID/Ad Unit ID شخصی هنوز دریافت یا فعال نشده است. SDK واسطه/mediation اضافه نشده است.

نسخهٔ25.5.0 در toolchain فعلی Kotlin1.9 به‌دلیل metadata2.3 کامپایل نشد. بررسی AAR رسمی24.0 نیز metadata2.1 نشان داد. APK آزمایشی با23.6.0 سازگار ساخته می‌شود؛ سری23 deprecated است و طبق جدول فعلی Google تا30ژوئن2027 sunset نمی‌شود. **پیش از انتشار واقعی Play، ابزار Kotlin/Compose/AGP و SDK تبلیغات باید به نسخهٔ پشتیبانی‌شده ارتقا یابند**؛ خطای metadata با skip flag دور زده نشده است.

برای فعال‌کردن تبلیغات واقعی: حساب AdMob، شناسه‌ها، readiness/store linking، consent با UMP و گزینهٔ مدیریت حریم خصوصی در مناطق لازم، app-ads.txt/سایت توسعه‌دهنده و اصلاح Data safety/privacy policy انجام شوند. صرف تعویض دو ID برای انتشار کافی نیست. موانع اولیهٔ Play (امضا/API36/native16KB) نیز در ANDROID_PLAY_PREPARATION.md باقی‌اند. درخواست بنر از مسیر معمول اینترنت انجام می‌شود؛ routing کاربران/اپ‌های دیگر برای درآمد تبلیغات تغییر نکرده است.

## آزمون و منابع

پنج آزمون JVM برای دو چرخه، شکست/تکرار، نشست قدیمی، foreground/Home، cooldown/تغییر ساعت و محدودکردن backlog اضافه شدند. آزمون Android ذخیرهٔ چند instance و impression/failure و آزمون بارگذاری Google demo با گزارش جدا نیز وجود دارند. نتیجهٔ واقعی دریافت creative وابسته به شبکه است و باید جدا از آزمون منطق گزارش شود.

- [Google test IDs](https://developers.google.com/admob/android/test-ads)
- [Banner integration](https://developers.google.com/admob/android/banner)
- [SDK release notes](https://developers.google.com/admob/android/rel-notes)
- [SDK deprecation/sunset](https://developers.google.com/admob/android/deprecation)

در توسعه روی تبلیغ واقعی کلیک نکن؛ این نسخه فقط از demo ID استفاده می‌کند.

نتیجهٔ نهایی1.0.1:39 آزمون JVM،4 آزمون متمایز Android و smoke واقعی minified Release پاس شدند. Google demo creative واقعاً نمایش داده شد؛ هنگام CONNECTING حذف شد و کنترل اتصال در دسترس ماند. loadAd و destroy صریحاً روی Main.immediate اجرا می‌شوند؛ خطای اولین اجرای تست روی thread نامناسب با همین اصلاح رفع شد. تست واقعی گوشی و تبلیغات درآمدزا انجام نشده‌اند.
