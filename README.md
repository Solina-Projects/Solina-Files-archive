# 📂 Solina Files (SolinaFM) v1.0.3

[![Release](https://img.shields.io/github/v/release/Solina-Projects/Solina-Files?color=006B5F&logo=android&label=Solina%20Files)](https://github.com/Solina-Projects/Solina-Files/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Solina-Projects/Solina-Files/build.yml?branch=main&label=Build%20APK)](https://github.com/Solina-Projects/Solina-Files/actions)
[![Language](https://img.shields.io/badge/Language-Kotlin%201.9-purple.svg?logo=kotlin)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/UI-Material%20Design%203-blue.svg)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-AGPL--3.0--or--later-0F3D2E)](LICENSE)

**Solina Files** یک فایل‌منیجر متن‌باز برای اندروید است که با تکیه بر **Material Design 3** و پشتیبانی بومی از زبان فارسی و تاریخ شمسی توسعه داده می‌شود. این شاخه برای یکپارچه‌سازی قابلیت‌های منتخب پروژهٔ Strata در ساختار فعلی Solina Files اختصاص دارد.

---

## 🚀 ویژگی‌های کلیدی

| قابلیت | شرح |
| :--- | :--- |
| 🎨 **طراحی Material You** | انطباق خودکار رنگ‌های برنامه با والپیپر دستگاه (Dynamic Colors) در اندروید ۱۲+ |
| 🇮🇷 **پشتیبانی بومی فارسی** | رابط کاربری راست‌به‌چپ (RTL) به همراه نمایش تاریخ‌های شمسی با تقویم جلالی |
| 🛡️ **خزانه امن (Vault)** | رمزگذاری داده‌ها با الگوریتم پیشرفته **AES-256-GCM** و احراز هویت بیومتریک |
| 🌐 **پروتکل‌های ذخیره‌سازی شبکه** | اتصال یکپارچه به سرورهای **FTP**, **SFTP**, **SMB (ویندوز)** و **WebDAV** |
| 📦 **فشرده‌سازی همه‌جانبه** | مدیریت و استخراج فرمت‌های **ZIP**, **7Z**, **RAR**, **TAR**, **GZ**, **XZ** با رمزنگاری |
| ⚡ **انتقال فایل Wi-Fi** | سرور اختصاصی HTTP برای انتقال سریع و بی‌سیم فایل‌ها بین گوشی و رایانه |
| 📱 **نمای دو پنله (Dual-Pane)** | مدیریت همزمان دو پوشه با قابلیت Drag & Drop |
| 🔧 **ابزارهای پیشرفته** | آنالیز حجم دیسک، محاسبه هش SHA-256، نمایشگر هگزادسیمال، ابزار بازرسی APK و مدیریت برنامه‌های نصب‌شده |
| ⚡ **پشتیبانی Root و Shizuku** | دسترسی به فایل‌های سیستمی از طریق پروتکل رسمی Shizuku و سوپریوزر |

---

## 📥 دانلود فایل APK آماده نصب

👉 **[دانلود مستقیم Solina Files v1.0.3 APK](https://github.com/Solina-Projects/Solina-Files/releases/download/v1.0.3/app-debug.apk)**

---

## 🛠️ نحوه کامپایل و راه‌اندازی پروژه

```bash
# ۱. کلون کردن مخزن
git clone https://github.com/Solina-Projects/Solina-Files.git
cd Solina-Files

# ۲. نیازمندی‌ها: JDK 17 و Gradle 8.7
# ۳. کامپایل نسخه Debug
gradle assembleDebug --no-daemon
```

---
**توسعه‌دهندهٔ Solina Files: تیم Solina**  
این شاخه تحت مجوز GNU AGPL نسخهٔ ۳ یا بالاتر منتشر می‌شود. اجزای شخص ثالث همچنان مشمول مجوزها و اطلاعیه‌های مربوط به خود هستند.

## انتساب پروژهٔ Strata

پروژهٔ [Strata](https://github.com/CluvexStudio/Strata) اثر CluvexStudio تحت مجوز GNU AGPL نسخهٔ ۳ یا بالاتر منتشر شده است. هر کد یا دارایی که از Strata وارد این پروژه شود باید اطلاعیه‌های حق‌نشر و مجوزهای مربوط را حفظ کند. این شاخهٔ ادغام در حال توسعه است؛ درج این انتساب به‌تنهایی به معنی تکمیل انتقال امکانات Strata نیست.


## تغییرات نسخه 1.0.3

- رفع کرش هنگام شروع برنامه: شناسهٔ نمای کارت حافظه با نوع ویوی مورد انتظار در کد یکسان نبود؛ فهرست میانبرهای حافظه اکنون در یک چیدمان افقی مجزا نمایش داده می‌شود.

## تغییرات نسخه 1.0.2

- اصلاح بررسی مسیر مقصد هنگام کپی پوشه و جلوگیری از گزارش موفقیت انتقال وقتی حذف مبدأ ناقص مانده باشد.
- پاک‌سازی فایل‌های ناقص خزانه در صورت شکست رمزگذاری یا در دسترس نبودن جریان ورودی.

## تغییرات نسخه 1.0.1

- اضافه‌شدن فهرست قابل جست‌وجوی برنامه‌های نصب‌شده؛ لمس برای اجرا و لمس طولانی برای بازکردن اطلاعات برنامه.
- بهبود پایداری ابزار تحلیل APK/DEX با محدودیت اندازه ورودی و پاک‌سازی فایل موقت.
- اصلاح خطاهای ساخت که در CI شناسایی شدند.

> این دانلود نسخه Debug برای آزمایش است، نه APK امضاشده با کلید انتشار تولیدی. حداقل نسخهٔ پشتیبانی‌شده اندروید ۱۱ (API 30) است.
