# Cook Mode

A real cook mode for a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) kitchen tablet. It connects straight to your recipe server and draws its own screens, so the recipe app is only the place the recipes live. There is no web page in a frame and no Home Assistant integration to install.

Tandoor Recipes and Norish are supported. Mealie, KitchenOwl and Grocy fit the same design: see [docs/DESIGN.md](docs/DESIGN.md).

> **Status:** early development. Not listed in Community Plugins yet. The views run in CI under Robolectric, with real drawing, but nothing has been tried on a real tablet yet. Norish support is tested against a stand-in server built from Norish's API and source, not yet against a running Norish.

## What it does

- **Today's meal, one tap away.** The first page shows today's meals from the meal plan, then the next days. A tap lands on exactly that recipe. The **Cook today's meal** action goes there without any tap, from a gesture or from Home Assistant.
- **No meal plan needed.** Every recipe is on the same page, with the recently opened ones, or with Norish the newest, and a search. The search has its own keys, Č, Š and Ž included, so it works on a kiosk with no Android keyboard.
- **Pictures.** Recipes and meals show their photos, loaded at the size they are shown so an old tablet copes.
- **A recipe before you start.** Its photo, times, ingredients to tick off, and servings that rescale every amount, including the amounts inside the steps. Chips warn of what is worth planning around: an overnight rest, a long wait, the oven's temperature.
- **One big step at a time.** The text grows to fill the screen for a short step and shrinks for a long one, within the size you choose, with the steps around it fading away. Swipe in any direction or press Next. Long imported steps split into paragraphs. Each step shows its own ingredients, and the whole list can stay open beside it.
- **Honest amounts.** The amounts the recipe app knows scale: Tandoor's, also inside the steps, and Norish's ingredient list with each step's share of it. A number simply typed into a step stays as written, so with changed servings the step says for how many servings its words were written.
- **Timers from the recipe's own words.** "Simmer for 10–15 minutes" shows the duration as a chip in the sentence: tap it and the timer runs. Several run at once, with pause, plus and minus one minute and an alarm that a touch anywhere stops. They keep running when you leave the recipe or close the panel, when a small card shows them over the dashboard and the screensaver. A black screensaver turns the screen off, so there the card shows only once a timer rings.
- **Read aloud.** The tablet's own voice, or Home Assistant's through the kiosk's announcements, which is how a kiosk gets a Slovenian voice, with no card over the step. Each step can read itself.
- **A screen that stays on.** The screensaver holds back while a recipe is up, until nobody has touched the tablet for as long as you choose, so a forgotten recipe does not keep it on all night.
- **Hands-free.** Next step, Previous step, Read step, Start the step's timer, One more minute and Stop the alarm are plugin actions for claps, corner taps and Show fingers. A ringing timer takes the first such command and only goes quiet, so a false clap cannot wipe what ended. The next one clears it. One more minute is the exception: it starts the timer again for a minute.
- **Finish.** After the last step it can add the meal to Tandoor's cook log. Norish keeps none.
- **It remembers.** Closing the panel keeps your place. Opening it again returns to the same step, until you end cooking. Closing the last page ends cooking, because the meal is ready. The step also survives a restart of the kiosk, unless it stays off for about 12 hours. The timers survive however long it stays off. A timer that ended less than half an hour ago rings when the kiosk comes back, and the plugin's page mentions an older one.

English and Slovenian, including Slovenian's four plural forms and decimal commas.

## Settings

| Group | Setting | What it does |
| --- | --- | --- |
| Recipe server | Recipe app | Tandoor or Norish. |
| | Server address | The address you open the app with in a browser. A Norish under a base path keeps it, such as `https://home.example/norish`. |
| | API token or key | Stored as a Kiosk Satellite secret. See [Creating the token or key](#creating-the-token-or-key). |
| | Test connection | Checks both, and says which app answered and how many recipes it has. |
| Meal plan | Days of the meal plan | Today and the days after it, 1 to 14. Norish shares only this week and this month, so with it the plan ends at the later of the two, and the first page says so. |
| Cooking | Size of a step's text | Normal, Large or Extra large. Short steps grow and long ones shrink within it. |
| | Split long steps | Shows each paragraph of a long step as a step of its own. |
| | Swipe to change steps | Turn off if a splashing hand keeps changing the step. |
| | Record the meal in Tandoor | Ask, Always or Never. Never also keeps opened recipes out of Tandoor's recently viewed list. Only with Tandoor. |
| | Language | Auto, English or Slovenščina. |
| Timers | Timer sound | Alarm tone, Beeps or Silent. A tablet with no alarm tone beeps. |
| | Sound stream | Which of the tablet's volumes it follows, Alarm or Media. |
| | Ring for at most | The sound stops after this long, 1 to 10 minutes. The timer stays on the screen. |
| | Test the timer sound | Rings for a few seconds and reports the volume, so a silent alarm shows before dinner. |
| | Corner of the timer card | Where the small card sits while the panel is closed. |
| | Timer card over everything | Also over the screensaver, except a black one until a timer rings, and over the kiosk's menu and its camera views. |
| Read aloud | Voice | Off, Tablet voice or Home Assistant. |
| | ESPHome node of this kiosk | Only if reading fails. See [Reading aloud](#reading-aloud). |
| | Read each step by itself | Otherwise only when asked. |
| Screen | Keep the screen on while cooking | Holds the screensaver back while a recipe is up. |
| | Let the screen rest after | How long without a touch before a forgotten recipe lets go, 15 to 240 minutes. |
| | Close the list when the screensaver starts | Only the first page. A recipe being cooked stays. |
| Advanced | Show advanced settings | Shows the row below. |
| | Saved session | Written by the plugin, for a restart. Emptied, it is forgotten at the next restart, unless a change writes it again first, or the save every half hour while a recipe is being cooked, every 65 minutes with Norish 0.25. End cooking and stopping the timers forget it for good. |

### Creating the token or key

With **Tandoor**:

1. Open **Settings > API** and create a token with the scope `read write`.
2. Copy it right after saving. Tandoor shows it only for a few seconds.
3. Paste it into **API token or key** and press **Test connection**.

A token with the scope `read` is enough to cook, but Tandoor then refuses the cook log and the recently viewed list.

With **Norish**:

1. Sign in as the user whose meal plan the kitchen follows. Norish shows each user the plan of their whole household, so a household member of its own for the tablets is the safest choice: a key can do anything its user can, also change recipes, and an admin's key reaches Norish's admin settings.
2. Open **Settings > User > API keys**, create a key, and copy all of it at once. Norish shows it only once. It has 64 letters.
3. Paste it into **API token or key** and press **Test connection**.

Norish allows each key 500 requests an hour, and every picture counts. Give each tablet its own key, so one busy tablet cannot use up another's. A Fleet Management profile that includes **Plugin secrets** would put the leader's key on every tablet, so keep Cook Mode out of such a profile and type each tablet's own key on it. Over the limit Norish asks the tablet to wait, and the plugin sends nothing with that key until the wait is over, pictures included.

Norish 0.26 or newer works best, and from 0.26.1 the plan also shows Norish's notes. Norish 0.25 starts its hour again at each request it allows, and refuses a key that has made 500 requests with no hour's pause as if the key were wrong. With 0.25 the plugin therefore reads the plan only every 65 minutes while nobody uses it, and half-hourly while the first page is in use. If 0.25 refuses a key that has worked, the plugin says so and waits an hour. After a restart it cannot tell that from a wrong key, so the panel says it may be 0.25's hour.

Norish counts its today, its week and its month in its own time zone, which is UTC unless `TZ` is set for its container. Set `TZ` to your own, such as `Europe/Ljubljana`, so its days change at your midnight.

### When Test connection fails

| What it says | What to do |
| --- | --- |
| Tandoor refused the API token | Create a new token. Tandoor 2.5.0 and 2.5.1 also need the scope `mealplan`, so use `read write mealplan` there, or update Tandoor. |
| Tandoor refused this address | From 2.6.1, Tandoor's `ALLOWED_HOSTS` is empty unless you set it. Add the host you use here, or the address you use in a browser. |
| Norish refused the API key | Create a new key and copy all of it. The message says when the one you pasted is shorter than Norish's keys. With Norish 0.25, a key that has worked may only be in its hour after 500 requests, so wait an hour before you replace it. |
| Norish is not ready | Norish cannot reach its database or its cache, and then it refuses every key. Its own logs say why. |
| Norish asks to wait | That key made its 500 requests within the hour. It works again after the time it names. |
| The plugin waits before it uses the key again | Norish 0.25 refused a key that had worked, which it does after 500 requests with no hour's pause. It works again within the hour. Norish 0.26 or newer does not do this. |
| Something in front of Norish answered HTTP 502 | Norish is stopped or still starting behind its proxy. The plugin tries again. |
| The address answered with a web page, or sent the request on | Check the address, with Norish's base path if it has one. A login page in front of the app needs an address that goes around it. The token or key is never sent on to another server. |
| This tablet cannot find the server | Android before version 12 cannot resolve `.local` names, so use the IP address. |
| The secure connection failed | Install a private CA in Android, which Kiosk Satellite trusts, or use plain http on the home network. Android 8.1 cannot use a server that only offers TLS 1.3. |
| The recipe server cannot be reached | Check the address from the tablet's own network. |

Opening one recipe can also say it is private and not shared with the token's user. That is about the recipe, not the token. Norish says such a recipe is no longer available, since it answers the same for both.

### Reading aloud

**Tablet voice** uses Android's text-to-speech. Not every tablet has one, few speak Slovenian, and Kiosk Satellite's permissions may keep it from working on Android 11 or newer. The plugin's page says when it cannot speak.

**Home Assistant** calls the kiosk's own action, `esphome.<node>_announce`, with the card turned off. The engine and voice are the ones under **Settings > ESPHome > Announcements** on the kiosk. The plugin works out the node from the device's name, so a kiosk called Letna Kuhinja is `ks_letna_kuhinja`. If yours is named otherwise, copy the middle of the action's name into **ESPHome node of this kiosk**.

## Actions and Home Assistant

| Action | What it does |
| --- | --- |
| Open Cook Mode, Close Cook Mode, Toggle Cook Mode | The panel. Opening wakes the screen and closes the screensaver. |
| Cook today's meal | Goes straight to the next meal planned today, reading the plan first when what it has is more than five minutes old, so a meal changed meanwhile counts. Norish's meals have no time, so they count as breakfast at 8:00, lunch at 12:00, dinner at 18:00 and a snack at 21:00. |
| Next step, Previous step | Move through the recipe. On the first page Next opens today's meal. With the panel closed or under a screensaver, the first one only brings it back. A ringing timer comes first, and the panel comes back after it is cleared. |
| Read step | Reads the current step aloud. |
| Start the step's timer | Starts the first of the step's timers that does not run yet. |
| One more minute | Starts a ringing timer again for a minute, or else adds a minute to the next one to ring. |
| Stop the alarm | Silences ringing timers. A second time clears them. |
| End cooking | Closes the panel and forgets the recipe. Timers keep running. |

Use them in **Gestures > Run a plugin action**, add them to the kiosk drawer, or turn them into Home Assistant buttons on the plugin's page. Gestures can also react to three plugin triggers: **A timer ended**, **Cooking started** and **Cooking finished**.

The plugin also listens for Kiosk Satellite's stop word, which should silence an alarm the same way. Kiosk Satellite describes it for its own alerts, so that is still to be tried.

With ESPHome entities on, the plugin adds:

| Entity | Shows |
| --- | --- |
| Select **Cook Mode view** | `Open` or `Closed`, and changing it opens or closes the panel. |
| Binary sensors **Cooking**, **Timer ringing** | Whether someone is cooking, and whether an alarm is on show. |
| Sensor **Timers** | How many timers exist. |
| Text sensors **Recipe**, **Step**, **Next timer**, **Next timer ends**, **Today's meals** | The recipe and step being cooked, the next timer with the time it ends, the same as a timestamp for a countdown, and today's meals. |

So an automation can dim the kitchen light when a timer rings, or show today's meals on a dashboard, with no recipe app integration in Home Assistant.

### Kiosk Mode

Kiosk Mode's exit gesture of several fast taps counts taps anywhere, also on the search keys, so fast typing can trigger it. Its variant that holds the last tap avoids this. The servings and timer buttons repeat while held, so they need no fast taps.

## Where data goes

- **Your recipe server.** The plugin calls the address in **Server address** with your token or key, to read the meal plan and recipes and, with Tandoor unless **Record the meal in Tandoor** is **Never**, to record what you opened and cooked. Norish's health check, which Test connection asks first, gets no key. The token or key never goes to any other host: a redirect to another host is not followed, nor to another port unless it goes from http up to https on the same host, nor from https back to http, and a next-page link from another host is rewritten to your server. With Fleet Management, Kiosk Satellite copies it to other kiosks only if a profile includes **Plugin secrets**. Plugin settings are not part of a configuration export, so a replaced tablet needs it again.
- **Pictures.** They load from wherever the app says they are, which can be S3 storage if you set that up in Tandoor. Each is fetched once and kept in a folder of Kiosk Satellite's cache on the tablet, at most 48 MB unless the pictures the panel shows take more, which Android may empty. Tandoor's signed S3 links change with every answer, so those are fetched each time and not kept. One the server says does not exist is not asked for again for a quarter of an hour, and one the tablet cannot show, such as AVIF before Android 12 or one over 8 MB, not until the plugin starts again. Tandoor's media is public, so the token is not sent with them. Norish's pictures need the key, so it goes with the pictures on your Norish server, under its base path, and with no others. While Norish has asked the plugin to wait, the pictures the tablet does not have yet wait too.
- **Home Assistant.** Only with the Home Assistant voice: the text of a step or the ingredient list goes through Kiosk Satellite's own connection to the kiosk's announce action, and on to whichever text-to-speech engine you chose, which may be a cloud service. The plugin never sees your Home Assistant token.
- **ESPHome entities.** With them on, the recipe, the step, the timers and today's meals go to Home Assistant.
- **The tablet.** With the tablet voice, Android's text-to-speech engine reads the step. Which engine that is, and what it does with the text, is up to the tablet. The **Saved session** setting holds the recipe's id and name, the servings, the step and the timers, in Kiosk Satellite's settings. It never holds the token. It is stored as a secret, so Fleet Management leaves it out unless a profile includes **Plugin secrets**. With Plugin secrets the leader's value replaces each follower's, so a follower's session does not survive a restart and every step syncs the fleet: keep Cook Mode out of such a profile, or turn Plugin secrets off in it and type the token on each kiosk.
- Nothing is sent anywhere else, and there is no telemetry.

## Installation

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/vednolacni/kiosk-satellite-cook-mode` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Once the plugin is listed in [Community Plugins](https://kiosksatellite.com/plugins/), this section gets the **Install in Kiosk Satellite** button.

Until there is a public release, take the ZIP from the latest [**Build** run on `main`](https://github.com/vednolacni/kiosk-satellite-cook-mode/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush), never from a run for a pull request, and install it with **Plugin Manager > Developer Tools > Install from ZIP**.

## Development

The plugin is built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins). `sdk/` and `tools/` are the template's, unchanged except for the test list in `tools/test.py`.

| Path | What it holds |
| --- | --- |
| `src/io/github/vednolacni/cookmode/` | `CookModePlugin` is the entry class. `Cook` is the controller, with no Android in it. `RecipeSource` is what a recipe app must offer, and `TandoorSource` and `NorishSource` are the two so far. `AndroidUi`, `CookView` and the `*Screen` classes are the views. |
| `tests/io/github/vednolacni/cookmode/` | Tests that run without a device, including against local HTTP servers that answer like Tandoor and like Norish. |
| `tests-views/` | The real views under Robolectric, with real drawing, touched the way a finger touches them through the whole of cooking, on the screens the kiosks have, down to Android 8.1. The **Views** workflow saves a screenshot of every page. |
| `docs/DESIGN.md` | How it fits together, what a real cook mode needs, how other recipe apps map, and what comes next. |

### Build

With Python 3, JDK 18 or newer and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

Without a local Android SDK, push to `main` or run **Build** by hand on your branch (Actions > Build > Run workflow). The run holds the ZIP as an artifact. A push to another branch starts no Build, and a pull request's run has no ZIP.

### Release

Publish a GitHub release with a tag such as `v0.1.0`. The **Release** workflow builds it and attaches the manifest, the ZIP and its checksum.

## License

[Apache-2.0](LICENSE). The icon is this project's own. Not affiliated with Kiosk Satellite, Tandoor Recipes, Norish or any other recipe app. The cooking view takes ideas from [Norish](https://norish.dev/), but no code, and `NorishSource` is written from Norish's API docs and its source, read only to learn how its API answers. No Norish code is in the plugin.
