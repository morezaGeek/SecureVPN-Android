# SecureVPN Android — آماده‌سازی نخستین انتشار Google Play

وضعیت بررسی: 2026-10-08. نسخهٔ نمایشی **1.0**، کد داخلی **199**، پکیج فعلی `com.vpnapp`.

تغییرات درخواست‌شدهٔ رابط، حذف کانفیگ آماده، بازیابی اعلان و سورس خصوصی آماده‌اند. APK این مرحله برای تست گوشی و ارتقا روی APKهای قبلی است؛ **هنوز فایل قابل ارسال به Play نیست**. انتشار در Play در این مرحله انجام نشده است.

## موانع فنی تأییدشده

| مورد | وضعیت فعلی | اقدام پیش از ارسال |
| --- | --- | --- |
| امضا | Release برای ارتقای تست‌های قبلی با Android Debug امضا می‌شود | ایجاد و پشتیبان‌گیری upload keystore مستقل و فعال‌کردن Play App Signing؛ ساخت AAB با امضای انتشار |
| Target API | compile/target SDK35، AGP8.5.0 | ارتقای سازگار ابزارها و compile/target SDK36 و آزمون تغییرات Android16 |
| ELF کتابخانه‌های arm64 | libbox:16KB؛ libconscrypt_jni/libopenconnect/libstoken:4KB | دریافت نسخهٔ سازگار یا بازسازی nativeها با NDK مناسب و آزمون دستگاه16KB؛ تغییر ZIP alignment به‌تنهایی کافی نیست |
| Bundle | APK تست چهار ABI ساخته می‌شود؛ هنوز AAB آمادهٔ Play نداریم | بعد از رفع موارد بالا ساخت bundle و بررسی APKهای تولیدشده با bundletool |

شواهد محلی: `scratch/android-1.0-native-audit.json`، APK استخراج‌شده با ELF program header واقعی. تنها alignment بررسی شده؛ این بررسی جای آزمون runtime روی دستگاه16KB را نمی‌گیرد. APK فعلی برای دستگاه‌های4KB قابل تست است. کتابخانهٔ libbox از نسخهٔ pinned `1.14.2-lx.11` آمده است.

نخستین انتشار بودن را کاربر تأیید کرده است. کلید debug با کلید Play جایگزین نشود تا فایل‌های تست موجود بی‌دلیل از قابلیت ارتقا خارج نشوند. کانال sideload و Play می‌توانند تنظیم امضای جدا داشته باشند؛ نصب نسخهٔ فروشگاه روی APK debug بدون uninstall امکان‌پذیر نیست. قبل از حذف اپ باید کاربر export بگیرد.

## اطلاعات لازم در مرحلهٔ Play Console

- نهایی‌کردن نام اپ و applicationId قبل از نخستین آپلود؛ پس از انتشار پکیج قابل تغییر نیست.
- حساب توسعه‌دهنده، نوع حساب و وضعیت تأیید هویت/آزمون موردنیاز همان حساب را در Console بررسی کنیم.
- متن فروشگاه، سیاست حریم خصوصی با نشانی عمومی، آیکون و feature graphic و اسکرین‌شات واقعی آماده شوند. تصاویر این مرحله از نصب خالی و بدون کانفیگ کاربر گرفته می‌شوند.
- فرم VpnService، foreground service/specialUse، دوربین برای QR، دسترسی اعلان و Data safety با رفتار واقعی کد تطبیق داده شوند. GeoIP از سرویس بیرونی استفاده می‌کند؛ ادعای «هیچ داده‌ای ارسال نمی‌شود» درست نیست.
- دسترسی `QUERY_ALL_PACKAGES` برای فهرست split tunneling نیاز به بررسی توجیه و سیاست Play دارد؛ در صورت کافی‌بودن launcher queries دسترسی محدودتر جایگزین شود.
- مجوزهای توزیع و attribution کتابخانه‌ها، مخصوصاً libbox/OpenConnect/stoken، بررسی شوند. این مخزن خصوصی مجوز توزیع تازه‌ای برای dependencies ایجاد نمی‌کند.

## اعلان اتصال

اعلان ongoing، action قطع و deleteIntent دارد. بررسی مستقل هر15ثانیه فقط اعلان مفقود همان نشست زنده را بازیابی می‌کند. اعلان مسدودشده از تنظیمات کاربر را فعال نمی‌کند. Android14+ به کاربر اجازهٔ حذف اعلان ongoing روی گوشی باز می‌دهد؛ تضمین غیرقابل‌سوایپ بودن با API معمول VPN وجود ندارد. آزمون واقعی سوایپ بدون delete callback، بازیابی با تونل متصل و عدم بازگشت پس از disconnect پاس شد. آزمون یک‌شبه روی مدل گوشی کاربر هنوز انجام نشده است.

## منابع رسمی، بررسی‌شده در 2026-10-08

- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en): ارسال اپ تازه از31اوت2026 API36 می‌خواهد؛ وضعیت هر extension در Console بررسی شود.
- [16KB page sizes](https://developer.android.com/guide/practices/page-sizes): هم ELF alignment و هم packaging و آزمون runtime لازم‌اند.
- [Android14 ongoing notifications](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications).
- [VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en).
- [Package visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779?hl=en).

گام بعد از تأیید تست گوشی: رفع موانع ابزار/native، تولید upload key خصوصی با backup امن، ساخت و آزمون AAB، سپس تکمیل فرم‌ها و انتشار آزمایشی با همراهی کاربر.
