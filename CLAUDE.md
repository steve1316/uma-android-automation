0. Never git add this CLAUDE.md file. It should never be pushed. It will only exist as a local file.
1. Commits should be small and concise.
2. The commit messages has no prefix, is imperative language, and has no extra bit at the end saying that it was co-authored.
3. The commit messages only consist of the first line of the commit message. No extra sentences on a newline / paragraph.
4. Python docstrings do not use double backticks, only single backticks.
5. Python docstrings must have Args, Raises and Returns sections if applicable.
6. When you make a new option in the frontend, it also needs to be added to the MessageLog.tsx settings string and the static search config.
7. Local location of the Library for reference of Library functionality is at `C:\Users\steve1316\Documents\GitHub\android-cv-automation-library\app\src\main\java\com\steve1316\automation_library`.
8. PR titles and descriptions should be Markdown in plain text inside a code block so I can copy it. PR descriptions should also be 1-3 sentences in length to summarize what we did and why we did it while not being too technical. PR descriptions also start with the "## Description" header followed by "- This PR ..." on a new line. In the descriptions as well, if you are referencing variables and functions names, surround them in backticks. For function names, use the format `function_name()`. For variable names, use the format `variable_name`.
9. `yarn format` needs to be run after code changes to ensure consistency.
10. Each function should have param and return tags in the docs. All functions have them.
11. Section comments in any source file (Kotlin, TypeScript, JavaScript, etc.) must use the following five-line format. Do NOT use shorter dash-bracketed forms like `// ---------------- Linearisation helpers ----------------` or `// -------- Helpers --------`:
    ```
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Section
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    ```
12. When diagnosing performance issues, use the React DevTools Profiler to identify bottlenecks as well as adb commands to interact with the app itself (usually connected via adb at 192.168.0.102:5555 emulator). Turn on `PerformanceLogger` to also help with gathering console logs.
13. When writing Kotlin code, do not pre-emptively make a line of code into a multi-line statement. `yarn format` will be the one to do that for you.
14. Create a new local branch for each new feature from master.
15. In the Pull Request description, if a log snippet was provided in the session, include a certain snippet of it to showcase what the original problem looked like. Make sure it's not too long, just enough to give context.