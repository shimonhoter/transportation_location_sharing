# Ride Location Share

אפליקציית אנדרואיד להסעה משותפת בבוקר (ללא נהג קבוע) — משדרת אוטומטית את
מיקום ההסעה לנוסעים הממתינים, תוך שמירה על פרטיות המיקום האישי של כל
נוסע. אין צורך בשיתוף מיקום ידני בוואטסאפ.

מסמכי האפיון המלאים (מצב נוכחי, לא היסטוריה):
[עברית](docs/SPEC_HE.md) | [English](docs/SPEC_EN.md)

## מבנה הריפו

- `app/` — אפליקציית האנדרואיד (Kotlin, WebView + MapLibre GL JS למפה).
- `server/` — שרת המיקום (Python, stdlib בלבד, ללא תלויות חיצוניות).
- `docs/` — מסמכי האפיון.
- `.github/workflows/` — בניית CI (`assembleDebug` + העלאת APK כ-artifact).

## הרצת שרת המיקום מקומית

```bash
RIDE_TOKEN=my-secret-token python3 server/server.py
```

ברירת המחדל היא פורט `8000`. ראו [`docs/SPEC_EN.md`](docs/SPEC_EN.md#42-location-server)
לפרטי ה-API.

## בניית האפליקציה

```bash
./gradlew assembleDebug
```

ה-APK החתום (עם `app/debug.keystore` הקבוע שבריפו) ייבנה תחת
`app/build/outputs/apk/debug/`.
