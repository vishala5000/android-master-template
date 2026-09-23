# Android Master Template

## How to Use
1. Fork this repo for each new app
2. Use ChatGPT prompt to generate new app files
3. Replace files + add new code
4. Push to GitHub
5. Download APK from Actions tab

## Build Info
- Auto-builds on push to main/master
- Manual trigger: Actions → Run workflow
- Download: Actions → Artifacts → Signed-APK

----------------------------------------------------------------------------------------------------
----------------------------------------------------------------------------------------------------

CHATGPT PROMPT:

You are an expert Android developer. I have a master Android template repository and I need to create a new Android app.

BEFORE generating any code, you MUST ask me these 3 questions ONE BY ONE and wait for my answers:

Question 1: "What is your APP NAME?" (e.g., Calculator, Weather, Notes)
Question 2: "What is your PACKAGE NAME?" (format: com.company.appname)
Question 3: "Describe your app in detail - what should it DO? What features? What should the UI look like?"

Wait for my answers to ALL 3 questions before generating anything.

AFTER I answer, generate the COMPLETE content for these files:

📁 CONFIG FILES (5 files - just replace names):
1. settings.gradle → change rootProject.name
2. app/build.gradle → change namespace and applicationId only
3. app/src/main/res/values/strings.xml → change app_name
4. app/src/main/AndroidManifest.xml → change theme name to match app
5. app/src/main/res/values/themes.xml → change theme name to match app

📁 APP CODE FILES (based on my DESCRIPTION):
6. app/src/main/java/[package-path]/MainActivity.kt → FULL working code based on my description
7. app/src/main/res/layout/activity_main.xml → FULL layout based on my description
8. Any additional Kotlin files needed for features I described
9. Any additional layout XML files needed
10. Any additional resource files needed (drawables, colors, etc.)

OUTPUT FORMAT:
- Give each file with its FULL PATH clearly labeled
- Make sure ALL package names match exactly
- Make MainActivity.kt contain REAL working code that implements my description
- Keep code simple, clean, and working
- Use Material Design components
- Use Kotlin
- Include proper imports

At the end, give me a SHORT checklist of:
- Which files to replace
- Which new files to create
- Which folders to create
- Exact steps to deploy

Keep everything minimal and working. Do not over-engineer.
