# Ride Location Share

אפליקציית אנדרואיד להסעה משותפת בבוקר (ללא נהג קבוע) — משדרת אוטומטית את
מיקום ההסעה לנוסעים הממתינים, תוך שמירה על פרטיות המיקום האישי של כל
נוסע. אין צורך בשיתוף מיקום ידני בוואטסאפ.

מסמכי האפיון המלאים (מצב נוכחי, לא היסטוריה):
[עברית](docs/SPEC_HE.md) | [English](docs/SPEC_EN.md)

## מבנה הריפו

- `app/` — אפליקציית האנדרואיד (Kotlin, WebView + MapLibre GL JS למפה).
- `docs/` — מסמכי האפיון.
- `.github/workflows/` — בניית CI (`assembleDebug` + העלאת APK כ-artifact).

## שרת המיקום

אין שרת נפרד — מיקום ההסעה נשמר ונקרא ישירות מ-**Firebase Realtime
Database** (node יחיד `rideLocation`, נדרס בכל עדכון, בלי היסטוריה),
עם **Firebase Anonymous Authentication** במקום טוקן משותף. ראו
[`docs/SPEC_EN.md`](docs/SPEC_EN.md#42-realtime-data-backend) לפרטים
ולהגדרת ה-Realtime Database Rules.

`app/google-services.json` קיים בריפו (הקובץ אינו סודי — הוא רק מזהה
ציבורי של פרויקט Firebase, מוגן ע"י כללי האבטחה). כתובת ה-Realtime
Database מוגדרת ב-`RideConfig.FIREBASE_DATABASE_URL`.

## בניית האפליקציה

```bash
./gradlew assembleDebug
```

ה-APK החתום (עם `app/debug.keystore` הקבוע שבריפו) ייבנה תחת
`app/build/outputs/apk/debug/`.
