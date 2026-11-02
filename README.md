# Cook Mode

A real cook mode for a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) kitchen tablet. It connects straight to your recipe server and draws its own screens, so the recipe app is only the place the recipes live. There is no web page in a frame and no Home Assistant integration to install.

Tandoor Recipes is supported today. Mealie, Norish, KitchenOwl and Grocy fit the same design: see [docs/DESIGN.md](docs/DESIGN.md).

> **Status:** early development. Not listed in Community Plugins yet, and not yet tried on a real tablet.

## What it does

- **Today's meal, one tap away.** The first page shows today's meals from the Tandoor meal plan, then the next days. A tap lands on exactly that recipe. The **Cook today's meal** action goes there without any tap, from a gesture or from Home Assistant.
- **No meal plan needed.** Every recipe is on the same page, with search and recently opened ones.
- **Pictures.** Recipes and meals show their photos, loaded at the size they are shown so an old tablet copes.
- **A recipe before you start.** Its photo, times, ingredients to tick off, and servings that rescale every amount, including the amounts inside the steps.
- **One big step at a time.** Three sizes of text, readable from across the kitchen, with the steps around it fading away. Swipe in any direction or press Next. Long imported steps split into paragraphs. Each step shows its own ingredients, and the whole list can stay open beside it.
- **Timers from the recipe's own words.** "Simmer for 10–15 minutes" shows the duration as a chip in the sentence: tap it and the timer runs. Several run at once, with pause, plus and minus one minute and an alarm that a touch anywhere stops. They keep running when you leave the recipe or close the panel, when a small card shows them over the dashboard and the screensaver.
- **Read aloud.** The tablet's own voice, or Home Assistant's through the kiosk's voice satellite, which is how a kiosk gets a Slovenian voice. Each step can read itself.
- **A screen that stays on.** The screensaver holds back while a recipe is up, until nobody has touched the tablet for as long as you choose, so a forgotten recipe does not keep it on all night.
- **Hands-free.** Next step, Previous step, Read step, Start the step's timer and Stop the alarm are plugin actions for claps, corner taps and Show fingers. The wake word's stop word silences an alarm too.
- **Finish.** After the last step it can add the meal to Tandoor's cook log.
- **It remembers.** Closing the panel keeps your place. Opening it again returns to the same step, until you end cooking.

English and Slovenian, including Slovenian's four plural forms and decimal commas.

## Settings

| Group | Setting | What it does |
| --- | --- | --- |
| Recipe server | Tandoor address | The address you open Tandoor with in a browser. |
| | API token | Stored as a Kiosk Satellite secret. See [Creating the token](#creating-the-token). |
| | Test connection | Checks both, and says which Tandoor answered and how many recipes it has. |
| Meal plan | Days of the meal plan | Today and the days after it, 1 to 14. |
| Cooking | Size of a step's text | Normal, Large or Extra large. |
| | Split long steps | Shows each paragraph of a long step as a step of its own. |
| | Timer sound | Plays the tablet's alarm tone at its alarm volume, for up to three minutes. |
| | Record the meal in Tandoor | Ask, Always or Never. Never also keeps opened recipes out of Tandoor's recently viewed list. |
| | Language | Auto, English or Slovenščina. |
| Read aloud | Voice | Off, Tablet voice or Home Assistant. |
| | Voice satellite | The kiosk's `assist_satellite` entity, for Home Assistant's voice. |
| | Read each step by itself | Otherwise only when asked. |
| Screen | Keep the screen on while cooking | Holds the screensaver back while a recipe is up. |
| | Let the screen rest after | How long without a touch before a forgotten recipe lets go, 15 to 240 minutes. |
| Panel | Close the list when the screensaver starts | Only the first page. A recipe being cooked stays. |
| | Corner of the timer card | Where the small card sits while the panel is closed. |

### Creating the token

1. In Tandoor, open **Settings > API** and create a token with the scope `read write`.
2. Copy it right after saving. Tandoor shows it only for a few seconds.
3. Paste it into **API token** and press **Test connection**.

A token with the scope `read` is enough to cook, but Tandoor then refuses the cook log and the recently viewed list.

### When Test connection fails

| What it says | What to do |
| --- | --- |
| Tandoor refused the API token | Create a new token. Tandoor 2.5.0 and 2.5.1 also need the scope `mealplan`, so use `read write mealplan` there, or update Tandoor. |
| Tandoor refused this address | From 2.6.1, Tandoor's `ALLOWED_HOSTS` is empty unless you set it. Add the host you use here, or the address you use in a browser. |
| The recipe server cannot be reached | Check the address from the tablet's own network. |

## Actions and Home Assistant

| Action | What it does |
| --- | --- |
| Open Cook Mode, Close Cook Mode, Toggle Cook Mode | The panel. Opening wakes the screen and closes the screensaver. |
| Cook today's meal | Goes straight to the next meal planned today. |
| Next step, Previous step | Move through the recipe. With the panel closed, the first one only brings it back. |
| Read step | Reads the current step aloud. |
| Start the step's timer | Starts the step's first timer. |
| Stop the alarm | Silences every ringing timer. |
| End cooking | Closes the panel and forgets the recipe. Timers keep running. |

Use them in **Gestures > Run a plugin action**, add them to the kiosk drawer, or turn them into Home Assistant buttons on the plugin's page. Gestures can also react to three plugin triggers: **A timer ended**, **Cooking started** and **Cooking finished**.

With ESPHome entities on, the plugin adds:

| Entity | Shows |
| --- | --- |
| Select **Cook Mode view** | `Open` or `Closed`, and changing it opens or closes the panel. |
| Binary sensors **Cooking**, **Timer ringing** | Whether someone is cooking, and whether an alarm sounds. |
| Sensor **Timers** | How many timers exist. |
| Text sensors **Recipe**, **Step**, **Next timer**, **Today's meals** | The recipe and step being cooked, the next timer with the time it ends, and today's meals. |

So an automation can dim the kitchen light when a timer rings, or show today's meals on a dashboard, with no Tandoor integration in Home Assistant.

## Where data goes

- **Your Tandoor server, only.** The plugin calls the address in **Tandoor address** with your token, to read the meal plan and recipes and, unless **Record the meal in Tandoor** is **Never**, to record what you opened and cooked. The token never goes to any other host: a redirect to another host is not followed, and a next-page link from another host is rewritten to your server.
- **Pictures.** They load from wherever Tandoor says they are, which can be S3 storage if you set that up. Tandoor's media is public, so the token is not sent with them.
- **Home Assistant.** Only with the Home Assistant voice: the text of a step goes through Kiosk Satellite's own connection, to `assist_satellite.announce` on the satellite you picked. The plugin never sees your Home Assistant token.
- **The tablet.** With the tablet voice, Android's text-to-speech engine reads the step. Which engine that is, and what it does with the text, is up to the tablet.
- Nothing is sent anywhere else, and nothing is kept beyond the plugin's settings. Timers and the recipe being cooked live in memory until the plugin stops.

## Installation

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/vednolacni/kiosk-satellite-cook-mode` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Once the plugin is listed in [Community Plugins](https://kiosksatellite.com/plugins/), this section gets the **Install in Kiosk Satellite** button.

Until there is a public release, take the ZIP from the latest run of the **Build** workflow and install it with **Plugin Manager > Developer Tools > Install from ZIP**.

## Development

The plugin is built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins). `sdk/` and `tools/` are the template's, unchanged except for the test list in `tools/test.py`.

| Path | What it holds |
| --- | --- |
| `src/io/github/vednolacni/cookmode/` | `CookModePlugin` is the entry class. `Cook` is the controller, with no Android in it. `RecipeSource` is what a recipe app must offer, and `TandoorSource` is the first one. `AndroidUi`, `CookView` and the `*Screen` classes are the views. |
| `tests/io/github/vednolacni/cookmode/` | Tests that run without a device, including against a local HTTP server that answers like Tandoor. |
| `docs/DESIGN.md` | How it fits together, what a real cook mode needs, how other recipe apps map, and what comes next. |

### Build

With Python 3, JDK 17 or newer and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

Without a local Android SDK, push to GitHub. The **Build** workflow runs the tests and attaches the ZIP to the run as an artifact.

### Release

Publish a GitHub release with a tag such as `v0.1.0`. The **Release** workflow builds it and attaches the manifest, the ZIP and its checksum.

## License

[Apache-2.0](LICENSE). Not affiliated with Kiosk Satellite, Tandoor Recipes or any other recipe app. The cooking view takes ideas from [Norish](https://norish.dev/), but no code.
