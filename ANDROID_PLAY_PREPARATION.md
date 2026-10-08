# SecureVPN Android — آماده‌سازی نخستین انتشار Google Play

وضعیت بررسی: 2026-10-08. نسخهٔ نمایشی **1.0.1**، کد داخلی **200**، پکیج فعلی `com.vpnapp`.

تغییرات درخواست‌شدهٔ رابط، حذف کانفیگ آماده، بازیابی اعلان و سورس خصوصی آماده‌اند. APK این مرحله برای تست گوشی و ارتقا روی APKهای قبلی است؛ **هنوز فایل قابل ارسال به Play نیست**. انتشار در Play در این مرحله انجام نشده است.

## موانع فنی تأییدشده

| مورد | وضعیت فعلی | اقدام پیش از ارسال |
| --- | --- | --- |
| امضا | Release برای ارتقای تست‌های قبلی با Android Debug امضا می‌شود | ایجاد و پشتیبان‌گیری upload keystore مستقل و فعال‌کردن Play App Signing؛ ساخت AAB با امضای انتشار |
| Target API | compile/target SDK35، AGP8.5.0 | ارتقای سازگار ابزارها و compile/target SDK36 و آزمون تغییرات Android16 |
| ELF کتابخانه‌های arm64 | libbox:16KB؛ libconscrypt_jni/libopenconnect/libstoken:4KB | دریافت نسخهٔ سازگار یا بازسازی nativeها با NDK مناسب و آزمون دستگاه16KB؛ تغییر ZIP alignment به‌تنهایی کافی نیست |
| Bundle | APK تست چهار ABI ساخته می‌شود؛ هنوز AAB آمادهٔ Play نداریم | بعد از رفع موارد بالا ساخت bundle و بررسی APKهای تولیدشده با bundletool |

## بررسی حساب‌ها و روند انتشار — 2026-10-08

- Play Console در مرورگر با حساب **MRE Developer / Personal** باز بود. فهرست حساب فقط اپ Persian & Gregorian Calendar را در Closed testing نشان می‌داد؛ SecureVPN هنوز app record ندارد.
- فرم Create app با نام نمایشی **SecureVPN** بررسی شد. Play اعلام کرد package فعلی `com.vpnapp` و نام عمومی `com.securevpn` قبلاً استفاده شده‌اند. نام `com.rahanetmci.securevpn` در بررسی availability آزاد بود، ولی مالکیت دامنهٔ `rahanetmci.com` هنوز از کاربر تأیید نشده؛ بنابراین هیچ packageای ثبت نشد. با package جدید، نصب‌های sideload فعلی ارتقای درجا نمی‌شوند و نصب Play هویت تازه‌ای خواهد داشت.
- فرم Play دو اظهارنامهٔ صاحب حساب می‌خواهد: انطباق اپ با سیاست‌های Play و مجازبودن صادرات نرم‌افزار رمزنگاری‌شده طبق قوانین آمریکا. این موارد تأیید نشده، checkboxها دست‌نخورده‌اند و app record ایجاد نشده است.
- با هدایت کاربر وارد حساب AdMob شدیم و اپ Android با نام نمایشی **SecureVPN** را به‌عنوان اپ هنوز منتشرنشده ثبت کردیم. واحد بنر `SecureVPN-Connection-Banner` هم ساخته شد. App ID: `ca-app-pub-5284715425712192~7524274903`؛ Banner ID: `ca-app-pub-5284715425712192/9988540732`. این اپ هنوز به Play وصل نیست و هیچ تبلیغ واقعی در بیلد تست فعال نشده است.
- انتشار عمومی اکنون به رفع سه مانع اصلی build نیاز دارد: target/compile API36، هم‌ترازی و آزمون همهٔ کتابخانه‌های native برای page size 16KB، و کلید upload خصوصی با Play App Signing. APKهای sideload با debug key امضا شده‌اند و نصب Play با کلید جدا می‌تواند برای ارتقا نیازمند حذف نسخهٔ sideload باشد.
- پس از build آمادهٔ Play، مراحل Console شامل Store listing و گرافیک/تصاویر، privacy policy عمومی و درون‌اپ، فرم Data safety با احتساب SDKهای تبلیغاتی، VpnService declaration و فیلم کوتاه جریان VPN/افشای روشن، طبقه‌بندی محتوا، تبلیغات، کشورهای انتشار و شروع Internal/Closed testing است. حساب‌های Personal که پس از 13نوامبر2023 ایجاد شده‌اند برای دسترسی Production به Closed test با حداقل12 آزمایشگر پیوسته برای14روز نیاز دارند؛ تاریخ ایجاد حساب را باید در Console بررسی کرد.
- AdMob: اپ و بنر ثبت شده‌اند. باقی‌مانده: ساخت پیام privacy/consent و مدیریت privacy choices در AdMob/UMP، واردکردن شناسه‌های واقعی فقط در build variant انتشار پس از تکمیل رضایت و افشاهای حریم خصوصی، اتصال اپ پس از انتشار به Play، افزودن وب‌سایت توسعه‌دهنده و انتشار/تأیید `app-ads.txt`، سپس درخواست app readiness review. تا پایان review نمایش کامل تبلیغ تضمین نیست.

### اطلاعات و تأییدهای لازم از صاحب حساب

1. تأیید دامنهٔ قابل‌استفاده برای Play package و Website/Privacy Policy/`app-ads.txt`. `com.rahanetmci.securevpn` فعلاً در availability آزاد دیده شد، اما تنها در صورت مالکیت همان دامنه باید انتخاب شود؛ package پس از ثبت قابل‌تعویض نیست.
2. تأیید رایگان‌بودن اپ (با توجه به درخواست تبلیغ، Free محتمل است) و مالکیت/تماس توسعه‌دهنده برای سیاست حریم خصوصی و صفحهٔ فروشگاه.
3. تأیید خود توسعه‌دهنده برای دو اظهارنامهٔ داخل فرم Create app: انطباق کامل با سیاست‌های Play، و مجازبودن صادرات نرم‌افزار رمزنگاری‌شده تحت قوانین مربوط. این گزاره‌ها را من به‌جای صاحب حساب تأیید نمی‌کنم.
4. نشست AdMob و ساخت اپ/واحد بنر انجام شده است؛ اگر Google برای پرداخت درآمد اطلاعات هویتی یا بانکی بخواهد، تکمیل آن را صاحب حساب انجام دهد.
5. شناسهٔ ناشر از App ID ثبت‌شده `pub-5284715425712192` است. برای `app-ads.txt` همچنان وب‌سایت عمومی توسعه‌دهنده لازم است؛ همان دامنه باید به Store listing متصل باشد و فایل پس از انتشار verify شود.

## مراحل رسمی

1. رفع build blockers بالا، ساخت upload key و AAB، آزمون smoke روی Android16/16KB و بررسی bundle.
2. ایجاد app record، تعیین نام و package ID نهایی، سپس تکمیل Store listing و contact/website.
3. بارگذاری Internal test، تأیید artifact و نصب، تکمیل privacy/Data safety/VpnService/ads/content rating declarations.
4. انتشار Closed test؛ اگر حساب شخصی مشمول قاعدهٔ جدید باشد، جمع‌کردن12 tester که14روز پیوسته opt-in بمانند و سپس درخواست Production access.
5. پس از پذیرش Production، ارسال Production release و rollout.
6. تکمیل AdMob: حساب و payment details، شناسه‌ها/واحد تبلیغ، consent، `app-ads.txt` و readiness review.

پیش‌نیاز آزمون شخصی: حساب Personal ایجادشده بعد از 13نوامبر2023 باید قبل از Production حداقل12 tester را14روز پیوسته در Closed test داشته باشد؛ [الزام رسمی آزمون حساب‌های شخصی](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en). Target API36 برای برنامه‌های جدید از31اوت2026 لازم است؛ [شرط Target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en). کتابخانه‌های 4KB با target35+، شرط [پشتیبانی 16KB](https://developer.android.com/guide/practices/page-sizes) را برآورده نمی‌کنند. VPNها باید [فرم VpnService](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en) را تکمیل و استفاده را در listing مستند کنند. [Data safety و privacy policy](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) حتی برای اپ‌های تست بسته نیز لازم‌اند، جز Internal-only؛ دادهٔ SDKهای ثالث هم باید بررسی شود.

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
- [UMP consent setup](https://developers.google.com/admob/android/privacy): وضعیت رضایت باید با UMP تازه شود و پیش از هر درخواست آگهی `canRequestAds()` بررسی شود. در AdMob هنوز پیام مقررات اروپا برای این اپ ساخته نشده است.

گام بعد از تأیید تست گوشی: رفع موانع ابزار/native، تولید upload key خصوصی با backup امن، ساخت و آزمون AAB، سپس تکمیل فرم‌ها و انتشار آزمایشی با همراهی کاربر.

## تبلیغات آزمایشی1.0.1

بنر demo اضافه شده است؛ درآمدزا نیست. پیش از انتشار واقعی، SDK23.6 deprecated با ارتقای هماهنگ Kotlin/Compose/AGP به نسخهٔ پشتیبانی‌شده ارتقا یابد؛ شناسه‌های واقعی AdMob، consent/UMP، سیاست حریم خصوصی و Data safety تکمیل شوند. جزئیات و شواهد تست در ANDROID_ADS.md ثبت‌اند. ممیزی native بالا متعلق به1.0 است؛ کتابخانه‌های VPN این مرحله تغییر نکرده‌اند.
