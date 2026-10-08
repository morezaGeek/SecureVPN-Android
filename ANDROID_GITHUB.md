# مخزن خصوصی اندروید

- GitHub: https://github.com/morezaGeek/SecureVPN-Android — private، شاخهٔ `main`.
- checkout مستقل محلی: `scratch/SecureVPN-Android-source`؛ این پوشه Git خودش را دارد. ریپوی ریشه همچنان متعلق به Windows است و app اندروید را ignore می‌کند. پوش کردن ریپوی Windows سورس اندروید را ارسال نمی‌کند.
- مبنای تاریخچه: snapshot پاک‌سازی‌شدهٔ1.3.68؛ تغییرات1.0/code199، آزمون‌ها، عکس‌ها و اسناد در commit بعدی ثبت شدند. کانفیگ واقعیِ helper قدیمی پیش از ساخت baseline حذف شد تا وارد تاریخچهٔ Git جدید نشود.
- کانفیگ شخصی، backup گوشی/شبیه‌ساز، فایل keystore و PAT داخل مخزن نیستند. توکن موجود پروژه فقط در فایل محلی `scratch/github-token.private.txt` خوانده می‌شود؛ مقدار آن در URL ریموت یا config ذخیره نشده است.
- libbox AAR با حجم120MB بالاتر از حد فایل معمول GitHub است؛ در Git نیست. `python scripts/fetch-libbox.py` نسخهٔ pinned را از upstream دانلود و SHA256 را بررسی می‌کند. provenance، wrapper Gradle، nativeهای کوچک OpenConnect/stoken و تمام سورس لازم موجودند.
- فایل‌های `local.properties` و `app/libs/libbox.aar` برای تأیید بیلد checkout محلی ایجاد شده‌اند و ignored هستند.

## ادامه با Antigravity

برای توسعهٔ مستقل اندروید، خود checkout بالا را باز کن و همان‌جا commit/push انجام بده. `app` ریشه و checkout کپی جدا هستند؛ تغییرات بعدی را همزمان در هر دو محل انجام نده. اگر کار را در ریشه ادامه دادی، تغییرات Android را با احتیاط به checkout منتقل کن و diff را قبل از commit بررسی کن. اسکریپت یک‌بارهٔ آماده‌سازی baseline را مجدداً اجرا نکن.

هر تغییر را در `ChatGPT ChangeLOG.md` ثبت کن و `versionCode` را بالاتر از200 ببر. نسخهٔ نمایشی پس از این مرحله از1.0 ادامه پیدا کند. کلید امضای تست فعلی را برای انتشار Play استفاده نکن؛ موانع دقیق در `ANDROID_PLAY_PREPARATION.md` هستند.

هیچ push به مخزن عمومی Windows در این مرحله انجام نشده و هیچ فایل Play آپلود نشده است.

نسخهٔ فعلی Android1.0.1/code200: بنر آزمایشی، شمارندهٔ اتمیک بین worker/UI و شواهد آزمون‌ها به سورس خصوصی منتقل شدند. README و verification.json همراه این مرحله به‌روز شدند.
