## LANragagi Missing Galleries
A quick and dirty program that connects to your LANraragi instance, and looks through publishers or tags on Fakku to tell you missing galleries and possible Nyaa.si links.

### How to run the program
It uses Java so make sure you have that installed

Example command:
```
java -jar LanraragiMissingGalleries-1.6.jar <lanraragi_api_key> <lanraragi link, something like http://192.168.0.5>
```
Additional modifiers can be added, such as:
- `debug` for debug output
- `filterJpTitles` to remove results that have a japanese character in the title
- `fakku_sid=<cookie>` for a Fakku sid cookie so that you can view filtered results
- `disableNyaaSearch` to disable searching Nyaa.si for alternative downloads