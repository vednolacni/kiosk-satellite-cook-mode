# Changelog

## 0.3.1

- **Added** Mealie notes that no step names, shown after the last step, where Mealie shows its notes.
- **Fixed** messages about Tandoor or Norish named a user name and password typed in front of the address. Only the host and port are named now, as for Mealie, also for an address that is not valid.
- **Fixed** a Mealie note a step names twice shows once.

## 0.3.0

Mealie, and what the Community Plugins review asked for.

- **Added** Mealie from v2.3 on, as the third recipe app. Its meal plan comes with all seven meal types named in English and Slovenian, notes, meals planned at a multiple of their recipe and meals for a whole week. Recently made lists what the household cooked last. A recipe brings its ingredient list with sections, each step's own lines and timer, and its times. Test connection says when Mealie is too old, and when a path after its address was left out, since Mealie runs only at the root of one.
- **Added** recording a meal in Mealie as the recipe's last made day, only when that is later than the day it has. The setting is now called Record the meal in Tandoor or Mealie.
- **Changed** a picture Mealie replaces is fetched again, though its address stays the same. What follows a `#` in an address is never sent, also not to a proxy.
- **Fixed** a recipe looked at for one planned meal and then opened for another meal with other servings, which kept the first meal's servings.
- **Fixed** a message about a redirect to another server named any user name and password in its address.
- **Changed** the ESPHome node for reading aloud is only letters, digits and underscores. The plugin no longer tries the kiosk's bare name without `ks_`, which could announce on another ESPHome device of that name.
- **Changed** a long recipe is paged and searched for oven and waiting hints on the plugin's own thread, so a large one cannot hold up the screen.
- **Changed** the pictures held in memory are let go when the panel closes. Those kept on the tablet stay.
- **Fixed** an error of any kind in the plugin's work on the screen or its own threads is caught there, so it cannot end Kiosk Satellite.

## 0.2.0

A clearer kitchen screen, checked with three design reviews.

- **Added** a Look setting. Warm kitchen gives the panel a cream or deep brown ground and a herb green, after Norish's look. The timer card over the dashboard keeps the kiosk's colors.
- **Changed** the timer card over the dashboard: the time comes first and large, with +N for more timers, and the card turns red with Stop when a timer rings. It showed a fifth and sixth timer it meant to leave out.
- **Changed** the timer sheet: delete is a bin set apart from pause, and a paused timer says so.
- **Changed** contrast throughout: text buttons, ingredient rings, the selected filter, meal types and the step previews read at 4.5:1, or 3:1 for marks.
- **Changed** the recipe page: a larger rounded photo, the overnight, long-wait and oven warnings right under the name, and units in their own color. The step shows its number in a circle and a rounded progress bar.
- **Changed** short screens keep the search results, warnings and End cooking in view.
- **Changed** a timer left running in another recipe names that recipe.
- **Fixed** TalkBack: the plus and minus buttons work with it, keys and servings buttons have names, ticked ingredients say so, and a step's timers can be started from it.
- **Fixed** scaled spoon amounts round to eighths, so no more 0,63.

## 0.1.0

The first release.

- **Added** a cook mode for Tandoor Recipes and Norish. Today's meals come first, then every recipe, with a search. A recipe shows its ingredients and servings that rescale, then cooks one step at a time, with timers from its own words, reading aloud and hands-free actions.
- **Added** Norish from 0.25 on. Its pictures are kept on the tablet, so its key pays for each once, and the plugin waits whenever Norish says the key has made its 500 requests for the hour.
- **Added** Home Assistant entities for the view, cooking, the recipe and step, the timers and today's meals.
- English and Slovenian. Needs Kiosk Satellite 2026.10.42 or newer.
