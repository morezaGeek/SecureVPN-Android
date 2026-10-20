## وضعیت قطعی آخرین ادامه — 2026-10-08

این بخش جایگزین وضعیت‌های قدیمیِ بخش‌های تاریخی زیر است. سورس انتشار Play در `scratch/SecureVPN-Android-source` است؛ سورس sideload ریشه در این مرحله به پکیج و ابزار انتشار Play منتقل نشده است.

- رکورد SecureVPN در Play Console ایجاد شده: `com.rahanetmci.securevpn`، رایگان با تبلیغات، نسخهٔ 1.0.0 / versionCode 1، compile/target SDK 36 و min SDK 26. namespace داخلی `com.vpnapp` است.
- کلید upload مستقل ساخته شده و فایل‌های کلید/رمز در سورس خصوصی ignored هستند. بکاپ امن جداگانهٔ آن‌ها ضروری است؛ کلید در پوشهٔ تحویل عمومی کپی نشده است.
- ابزار نهایی: AGP 8.13.2، Gradle 8.13، Kotlin و Compose compiler plugin 2.3.21، JDK 17، Mobile Ads 25.5.0، UMP 4.0.0. مهاجرت compilerOptions انجام شد؛ suppress compatibility flag حذف شد.
- `testDebugUnitTest bundleRelease` موفق: 46 تست واحد در 16 suite، بدون failure/error. چهار تست Android روی شبیه‌ساز Android 14 نیز پاس شدند: نصب خالی، ماندگاری چرخهٔ تبلیغ و بارگذاری یا شکست کنترل‌شدهٔ بنر آزمایشی بدون مسدودکردن کنترل اتصال. نمایش قطعی آگهی واقعی یا آزمون درآمد ادعا نمی‌شود.
- AAB با upload key امضا شده، bundletool validate و بررسی امضا موفق‌اند. بستهٔ نهایی 139194900 بایت است؛ SHA-256: `6dd7186fbf58b48f9d013605e62cb1ee08cb3c2fae45c7e7546f0b9cb6b7be8d`.
- فایل تحویل: `H:/Antigravity Projects/VPN APP/SecureVPN-Play-1.0.0/SecureVPN-1.0.0-play.aab`. این فایل برای Play است و مستقیماً مانند APK نصب نمی‌شود. نصب smoke از APK ساخته‌شده با bundletool موفق بود؛ دادهٔ اولیه هیچ پروفایل یا ساب آماده‌ای ندارد.
- آیکون فروشگاه اصلاح شد: زمینهٔ charcoal یکپارچه، سپر teal بزرگ و globe/check سفید؛ قاب و گردی بیرونی از خود Play اعمال می‌شود. feature graphic هماهنگ ساخته شد. تولید مجدد با `scripts/generate-store-assets.ps1`؛ آیکون launcher بومی در این مرحله تغییر نکرده است.
- متن فروشگاه، آیکون 512×512، feature graphic 1024×500 و چهار screenshot واقعی home/settings در تم روشن/تیره در Console آماده‌اند. در فرم AI label فقط آیکون و feature انتخاب شده‌اند؛ screenshots واقعی انتخاب نشده‌اند. دکمهٔ نهایی Label assets and submit و ذخیرهٔ نهایی listing هنوز زده نشده‌اند و منتظر تأیید لحظه‌ای کاربر هستند.
- Data safety به‌صورت draft ذخیره شده؛ اظهارنامه‌های قبلاً تأییدشده نیز ذخیره شده‌اند. ارسال برای review، upload AAB و ایجاد release هنوز انجام نشده است.
- متن به‌روز privacy شامل رمزنگاری تونل و HTTPS ساب‌ها در سورس آماده است. ZIP پنج‌فایلی در فرم production سایت Cloudflare Pages بارگذاری شده؛ Save and deploy هنوز زده نشده و تأیید آن در انتظار پاسخ است. متن آنلاین هنوز نسخهٔ قبلی است؛ DNS تغییر نکرده است.
- ابزار Computer Use این نوبت را به‌دلیل ناتوانی در تشخیص مطمئن URL مرورگر برای اعمال سیاست متوقف کرد. هیچ ارسال یا deploy نهایی انجام نشد. در ادامهٔ بعدی ابتدا وضعیت تازهٔ مرورگر و پاسخ تأییدها خوانده شود؛ از indexهای UI قدیمی استفاده نشود.
- Production این حساب هنوز قفل است: Console به 12 آزمایشگر با عضویت پیوستهٔ 14 روز در closed testing نیاز دارد؛ فعلاً صفر آزمایشگر است. انتشار فوری عمومی ممکن نیست.
- باقی‌مانده: تأیید/ثبت نهایی listing و سایت، ایجاد و انتشار پیام consent AdMob، بارگذاری AAB و بررسی خطاهای Console، اظهارنامه و فیلم VpnService/foreground service در صورت درخواست، انتخاب آزمایشگران و شروع closed test، سپس درخواست دسترسی Production و review.
- بررسی native با `scripts/audit-bundle-native.py`: libbox در ABIهای 64-bit دارای PT_LOAD alignment 16KB است؛ libconscrypt_jni/libopenconnect/libstoken هنوز 4KB هستند. PAGE_ALIGNMENT_16K در BundleConfig به‌تنهایی سازگاری ELF یا اجرای 16KB را اثبات نمی‌کند. بازسازی/جایگزینی nativeها و آزمون روی runtime 16KB باقی است؛ نباید انطباق کامل را ادعا کرد.
- در این نوبت commit یا push Git انجام نشده است.

### یادداشت رفع خطاهای ساخت

Gradle با JDK 25 و TEMP بلند sandbox در socket محلی شکست می‌خورد؛ JDK 17، GRADLE_USER_HOME اختصاصی و TEMP/TMP کوتاه `scratch/build-tmp` به‌همراه اجازهٔ اجرای socket محلی استفاده شد. import جاافتادهٔ TunnelEncryptionPolicy اضافه شد. برای metadata Kotlin 2.3، AGP به 8.13.2 و Gradle به 8.13 ارتقا یافت؛ build نهایی بدون هشدار ناسازگاری R8 metadata موفق شد.

## سوابق تاریخی — وضعیت‌های قدیمی زیر ممکن است منسوخ باشند
# SecureVPN Android — آماده‌سازی نخستین انتشار Google Play

وضعیت بررسی: 2026-10-08. نسخهٔ نمایشی **1.0.1**، کد داخلی **200**. شناسهٔ بستهٔ انتخاب‌شده برای Google Play برابر `com.rahanetmci.securevpn` است؛ namespace داخلی Kotlin همچنان `com.vpnapp` می‌ماند.

تغییرات درخواست‌شدهٔ رابط، حذف کانفیگ آماده، بازیابی اعلان و سورس خصوصی آماده‌اند. APK این مرحله برای تست گوشی و ارتقا روی APKهای قبلی است؛ **هنوز فایل قابل ارسال به Play نیست**. انتشار در Play در این مرحله انجام نشده است.

## موانع فنی تأییدشده

| مورد | وضعیت فعلی | اقدام پیش از ارسال |
| --- | --- | --- |
| امضا | Release تست با Android Debug امضا می‌شود؛ upload keystore یا AAB نداریم | ساخت و بکاپ امن upload keystore مستقل، فعال‌کردن Play App Signing و ساخت AAB امضاشده |
| Target API | build files به compile/target SDK36، AGP8.10.1 و Gradle8.11.1 ارتقا یافته‌اند؛ هنوز build این ترکیب تأیید نشده | build و آزمون کامل با Android16؛ رفع خطاهای migration پیش از upload |
| کتابخانه‌های 16KB | libbox:16KB؛ libconscrypt_jni/libopenconnect/libstoken:4KB | سازگاری روی دستگاه‌های 16KB باقی است. Google Play از 1فوریه2027 انتشار update ناسازگار را متوقف می‌کند؛ تا آن زمان نسخه‌های native سازگار بگیر/بازسازی کن و runtime را روی image 16KB تست کن. این مهلت، blocker فوری ارسال اولیه در اکتبر2026 نیست. |
| Bundle | APK تست چهار ABI داریم؛ AAB آمادهٔ Play نداریم | پس از build موفق، AAB را تولید، امضا و با bundletool بررسی کن |

## بررسی حساب‌ها و روند انتشار — 2026-10-08

- Play Console در مرورگر با حساب **MRE Developer / Personal** باز بود. فهرست حساب فقط اپ Persian & Gregorian Calendar را در Closed testing نشان می‌داد؛ SecureVPN هنوز app record ندارد.
- فرم Create app با نام نمایشی **SecureVPN** بررسی شد. `com.rahanetmci.securevpn` آزاد بود و کاربر آن را انتخاب کرد. سورس Play با این applicationId به‌روزرسانی شد؛ namespace داخلی `com.vpnapp` ماند. نصب‌های sideload فعلی ارتقای درجا نمی‌شوند و نصب Play هویت تازه‌ای خواهد داشت.
- کاربر تأیید کرد اپ رایگان و دارای تبلیغات باشد؛ پس از انتشار رایگان، تغییر به قیمت پولی ممکن نیست. نوع «App» برای این VPN انتخاب می‌شود.
- کاربر اظهارنامه‌های سازگاری سیاست‌های Play و مجازبودن صادرات نرم‌افزار رمزنگاری‌شده را صریحاً تأیید کرد و خواست بررسی فنی هم انجام شود. checkboxها هنوز تیک نخورده‌اند و app record هنوز ایجاد نشده است.
- با هدایت کاربر وارد حساب AdMob شدیم و اپ Android با نام نمایشی **SecureVPN** را به‌عنوان اپ هنوز منتشرنشده ثبت کردیم. واحد بنر `SecureVPN-Connection-Banner` هم ساخته شد. App ID: `ca-app-pub-5284715425712192~7524274903`؛ Banner ID: `ca-app-pub-5284715425712192/9988540732`. این اپ هنوز به Play وصل نیست و هیچ تبلیغ واقعی در بیلد تست فعال نشده است.
- موانع ارسال فعلی: build موفق با target/compile API36 و ساخت AAB با upload key پایدار. بررسی 16KB باید انجام شود، اما مهلت توقف انتشار update ناسازگار 1فوریه2027 است. applicationId جدید باعث نصب جداگانه از APKهای sideload می‌شود.
- بررسی فنی مقدماتی، دو اصلاح سیاست‌محور را وارد سورس کرد: افشای روشن VPN پیش از نمایش اجازهٔ سیستمی VPN، و محدودکردن Package Visibility به اپ‌های دارای launcher entry (حذف `QUERY_ALL_PACKAGES`). سرویس foreground ویژه نیز subtype مشخص در Manifest گرفت. این تغییرات هنوز build یا روی دستگاه آزموده نشده‌اند.
- پس از build آمادهٔ Play، مراحل Console شامل Store listing و گرافیک/تصاویر، privacy policy عمومی و درون‌اپ، فرم Data safety با احتساب SDKهای تبلیغاتی، VpnService declaration و فیلم کوتاه جریان VPN/افشای روشن، طبقه‌بندی محتوا، تبلیغات، کشورهای انتشار و شروع Internal/Closed testing است. حساب‌های Personal که پس از 13نوامبر2023 ایجاد شده‌اند برای دسترسی Production به Closed test با حداقل12 آزمایشگر پیوسته برای14روز نیاز دارند؛ تاریخ ایجاد حساب را باید در Console بررسی کرد.
- AdMob: اپ و بنر ثبت شده‌اند. باقی‌مانده: ساخت پیام privacy/consent و مدیریت privacy choices در AdMob/UMP، واردکردن شناسه‌های واقعی فقط در build variant انتشار پس از تکمیل رضایت و افشاهای حریم خصوصی، اتصال اپ پس از انتشار به Play، افزودن وب‌سایت توسعه‌دهنده و انتشار/تأیید `app-ads.txt`، سپس درخواست app readiness review. تا پایان review نمایش کامل تبلیغ تضمین نیست.
- وب‌سایت دوزبانهٔ Privacy/Support و `app-ads.txt` در Cloudflare Pages منتشر شده است: `https://securevpn.rahanetmci.com`. هر چهار مسیر اصلی HTTPS بررسی شدند. CNAME زیردامنه به Pages متصل است؛ وضعیت داشبورد هنوز Verifying بود و ممکن است تا پایان انتشار DNS تغییر کند. اتصال وب‌سایت به Store listing و تأیید `app-ads.txt` در AdMob همچنان بعد از ساخت اپ/انتشار Play باقی است؛ رکوردهای VPN تغییر نکردند.

### اطلاعات و تأییدهای لازم از صاحب حساب

1. مالکیت دامنه، ایمیل تماس، package `com.rahanetmci.securevpn` و قیمت رایگان با تبلیغات از کاربر تأیید شده‌اند.
2. کاربر دو اظهارنامهٔ داخل فرم Create app را نیز تأیید کرده است: انطباق با سیاست‌های Play و مجازبودن صادرات رمزنگاری. قبل از تیک‌زدن و ارسال فرم باید تأیید لحظه‌ای action-time دریافت شود؛ مسئولیت حقوقی متعلق به صاحب حساب است.
3. build فایل‌ها به API36 ارتقا یافته‌اند اما compile تأیید نشده؛ کلید Play و AAB هنوز وجود ندارند.
4. نشست AdMob و ساخت اپ/واحد بنر انجام شده است؛ اگر Google برای پرداخت درآمد اطلاعات هویتی یا بانکی بخواهد، تکمیل آن را صاحب حساب انجام دهد.
5. شناسهٔ ناشر از App ID ثبت‌شده `pub-5284715425712192` است. برای `app-ads.txt` همچنان وب‌سایت عمومی توسعه‌دهنده لازم است؛ همان دامنه باید به Store listing متصل باشد و فایل پس از انتشار verify شود.

## مراحل رسمی

1. build فایل‌ها با Gradle8.11.1/AGP8.10.1 را اجرا و آزمون smoke با Android16 انجام بده؛ upload key و AAB را پس از آماده‌سازی بساز.
2. پس از تأیید لحظه‌ای اظهارنامه‌ها app record را با نام و package انتخاب‌شده ایجاد کن و Store listing/contact/website را کامل کن.
3. بارگذاری Internal test، تأیید artifact و نصب، تکمیل privacy/Data safety/VpnService/ads/content rating declarations.
4. انتشار Closed test؛ اگر حساب شخصی مشمول قاعدهٔ جدید باشد، جمع‌کردن12 tester که14روز پیوسته opt-in بمانند و سپس درخواست Production access.
5. پس از پذیرش Production، ارسال Production release و rollout.
6. تکمیل AdMob: حساب و payment details، شناسه‌ها/واحد تبلیغ، consent، `app-ads.txt` و readiness review.

پیش‌نیاز آزمون شخصی: حساب Personal ایجادشده بعد از 13نوامبر2023 باید قبل از Production حداقل12 tester را14روز پیوسته در Closed test داشته باشد؛ [الزام رسمی آزمون حساب‌های شخصی](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en). Target API36 برای برنامه‌های جدید از31اوت2026 لازم است؛ [شرط Target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en). برای target35+، Google Play سازگاری 16KB را لازم می‌داند، اما توقف release update ناسازگار از1فوریه2027 شروع می‌شود؛ [پشتیبانی 16KB](https://developer.android.com/guide/practices/page-sizes). VPNها باید [فرم VpnService](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en) را تکمیل و استفاده را در listing مستند کنند. [Data safety و privacy policy](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) حتی برای اپ‌های تست بسته نیز لازم‌اند، جز Internal-only؛ دادهٔ SDKهای ثالث هم باید بررسی شود.

شواهد محلی: `scratch/android-1.0-native-audit.json`، APK استخراج‌شده با ELF program header واقعی. تنها alignment بررسی شده؛ این بررسی جای آزمون runtime روی دستگاه16KB را نمی‌گیرد. APK فعلی برای دستگاه‌های4KB قابل تست است. کتابخانهٔ libbox از نسخهٔ pinned `1.14.2-lx.11` آمده است.

نخستین انتشار بودن را کاربر تأیید کرده است. کلید debug با کلید Play جایگزین نشود تا فایل‌های تست موجود بی‌دلیل از قابلیت ارتقا خارج نشوند. کانال sideload و Play می‌توانند تنظیم امضای جدا داشته باشند؛ نصب نسخهٔ فروشگاه روی APK debug بدون uninstall امکان‌پذیر نیست. قبل از حذف اپ باید کاربر export بگیرد.

## اطلاعات لازم در مرحلهٔ Play Console

- نهایی‌کردن نام اپ و applicationId قبل از نخستین آپلود؛ پس از انتشار پکیج قابل تغییر نیست.
- حساب توسعه‌دهنده، نوع حساب و وضعیت تأیید هویت/آزمون موردنیاز همان حساب را در Console بررسی کنیم.
- متن فروشگاه، سیاست حریم خصوصی با نشانی عمومی، آیکون و feature graphic و اسکرین‌شات واقعی آماده شوند. تصاویر این مرحله از نصب خالی و بدون کانفیگ کاربر گرفته می‌شوند.
- فرم VpnService، foreground service/specialUse، دوربین برای QR، دسترسی اعلان و Data safety با رفتار واقعی کد تطبیق داده شوند. GeoIP از سرویس بیرونی استفاده می‌کند؛ ادعای «هیچ داده‌ای ارسال نمی‌شود» درست نیست.
- `QUERY_ALL_PACKAGES` از Manifest حذف شد؛ فهرست Split Tunneling فقط برنامه‌های دارای launcher entry را از طریق `<queries>` هدفمند می‌خواند. Privacy Policy با همین دامنهٔ محدود به‌روز شد.
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

## وضعیت جاری Play Console و ممیزی رمزنگاری — 2026-10-08

- SecureVPN اکنون app record با شناسهٔ `com.rahanetmci.securevpn` دارد. این رکورد و فرم‌ها در پیش‌نویس‌اند؛ هنوز AAB بارگذاری یا اپ برای بازبینی ارسال نشده است.
- در Console، اظهارنامه‌های Advertising ID (بله؛ تبلیغات، تحلیل و ضدتقلب)، Government apps (خیر)، Financial features (بدون قابلیت مالی)، Health apps (بدون قابلیت سلامت) و Target audience (۱۳–۱۵، ۱۶–۱۷ و ۱۸+) ذخیره شدند. پس از ذخیرهٔ مخاطب، Data safety همچنان نیازمند تکمیل است.
- ممیزی فنی، VLESS فاقد TLS/REALITY/VLESS Encryption، VMess با `none`/`zero` و بدون TLS، و Shadowsocks با روش `none` را بدون رمزنگاری مسیر دستگاه تا endpoint یافت. برنامه حالا چنین پروفایل‌هایی را پیش از اتصال رد می‌کند و در سازندهٔ outbound نیز guard دارد.
- متن disclosure درون‌برنامه و Privacy Policy دوزبانه برای توضیح الزام رمزنگاری و رد پروفایل ساده اصلاح شد. اجرای `testDebugUnitTest` در این محیط کامل نشد چون Gradle نتوانست loopback محلی را برقرار کند؛ باید تست در Android Studio یا میزبان build عادی تکرار شود.
- تصویر ارسالی برای `securevpn-site.mo-reza2011.workers.dev/index.html` خطای Cloudflare 1101 نشان می‌دهد. این URL مربوط به Worker آزمایشی است و نباید به‌عنوان privacy URL در Play ثبت شود. دامنهٔ سفارشی Pages، `https://securevpn.rahanetmci.com/privacy/`، طبق لاگ قبلی باز شده بود و باید پیش از ارسال نهایی دوباره زنده بررسی شود.
- مانع‌های پیش از Play upload: تکمیل Data safety با لحاظ AdMob و درخواست‌های IP/Geo، ساخت و انتشار پیام رضایت AdMob، بررسی آنلاین Privacy/Support/`app-ads.txt`، تکمیل VpnService declaration و listing/اسکرین‌شات، ساخت upload key و AAB امضاشده با API36، اجرای تست‌ها و Closed testing طبق الزام حساب شخصی. در این مرحله چیزی برای بازبینی یا انتشار فرستاده نشد.

### بررسی رمزنگاری اشتراک‌ها — 2026-10-08

- `V2RayLinkParser.fetchSubscription` دیگر `HttpURLConnection` عمومی و redirect خودکار ندارد؛ لینک اشتراک باید HTTPS باشد و redirectهای HTTP (از جمله downgrade) رد می‌شوند. redirectهای HTTPS تا پنج مرحله دنبال می‌شوند و connection در تمام مسیرهای خروج بسته می‌شود.
- Privacy Policy فارسی و انگلیسی می‌گوید URL اشتراک، که ممکن است توکن داشته باشد، روی HTTPS به ارائه‌دهنده می‌رود و HTTP پذیرفته نیست.
- تست واحد برای HTTPS، رد HTTP و redirect امن/ناامن اضافه شد. هنوز به دلیل خطای loopback Gradle اجرا نشده است.
- اقدام بعدی در Data safety: انتخاب رمزنگاری در مسیر و «برنامه امکان ساخت حساب ندارد»؛ داده‌های SDK AdMob و IP/Geo در مرحلهٔ Data types باید جداگانه اعلام شوند. فرم هنوز ذخیره نشده است.
