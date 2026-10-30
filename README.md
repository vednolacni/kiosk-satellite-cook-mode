# Kitchen View

A kitchen view for a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) wall tablet: today's and this week's meals from any Home Assistant calendar, recipes opened full screen from your own recipe app and a cook mode that keeps the screensaver away while you cook.

Kitchen View does not talk to any recipe app itself. It reads the meal plan from Home Assistant `calendar` entities, which Tandoor, Mealie and other apps already fill through their Home Assistant integrations, and it opens the address you give it. So it works with any recipe app.

> **Status:** early development. Not listed in Community Plugins yet.

## What it does

- **Meal plan panel.** A full screen panel with today's meals as large cards and the next days below or beside them. It opens like the built-in calendar and camera views: from a gesture, the kiosk drawer, Home Assistant or the panel's own select.
- **Recipes.** Tapping a meal opens its recipe as its own full screen page, not in a frame, so the recipe app's login keeps working. The page has Kiosk Satellite's close button, which brings back the dashboard.
- **Cook mode.** While a recipe is open, the screensaver stays away. Closing the recipe ends it.

### Where a meal leads

1. A link in the event's description or location, such as `https://recipes.example/view/recipe/12`.
2. Otherwise the **Recipe search address** with the dish filled in, if you set one.
3. Otherwise the **Recipe app address**.

An event called `Dinner: Lasagna` shows as *Dinner* above *Lasagna*, and the search uses *Lasagna*.

## Settings

| Setting | What it does |
| --- | --- |
| Meal plan calendars | The `calendar` entities to show, each with an optional label and color. |
| Days to show | Today and the days after it, 1 to 14. |
| Split the meal type from the dish | Shows `Dinner: Lasagna` as *Dinner* above *Lasagna*. |
| Recipe app address | Home page of your recipe app, such as `http://192.168.1.20:8080`. |
| Recipe search address | Optional. A search page with `{title}` where the dish goes, such as `http://192.168.1.20:8080/search/?query={title}`. |
| Cook mode | Keeps the screensaver away while a recipe is open. |
| Longest cooking session | Only for Kiosk Satellite releases that cannot hold the screensaver for a page. See [Cook mode](#cook-mode). |
| Panel title | Leave empty for *Meal plan*. |
| Language | Auto, English or Slovenščina. |
| Close when the screensaver starts | Closes the panel when the kiosk goes idle, like the built-in views. |

## Actions and Home Assistant

| Action | What it does |
| --- | --- |
| Show recipes | Opens the panel, waking the screen and closing the screensaver. |
| Hide recipes | Closes the panel. |
| Toggle recipes | Opens or closes the panel. |
| Open recipe app | Opens the recipe app's home page in cook mode. |
| End cooking | Closes the recipe page and ends cook mode. |

Use them in **Gestures > Run a plugin action**, add them to the kiosk drawer, or turn them into Home Assistant buttons on the plugin's page. With ESPHome entities on, the plugin also adds a **Recipes view** select with `Open` and `Closed`, like the kiosk's **Calendar view** select. Entity IDs come from Kiosk Satellite. Rename them in Home Assistant if you like, for example to `button.kitchen_show_recipes`.

## Cook mode

Kiosk Satellite can hold its screensaver for a link page and let go when the page closes, however it closes. Kitchen View asks for that hold with every recipe.

A Kiosk Satellite release that refuses the hold from plugins gets a cooking session instead. The plugin reports activity just before each screensaver deadline, and an **End cooking** button floats over the recipe. Kiosk Satellite does not tell plugins when its page closes, so the session ends when you press **End cooking** or when **Longest cooking session** runs out.

## Where data goes

- The plugin reads calendar events through Kiosk Satellite's own Home Assistant connection, with `calendar.get_events`. It never sees the Home Assistant token.
- It opens only the addresses in your settings and the links in your calendar events, in Kiosk Satellite's own page view.
- It sends nothing anywhere else and keeps nothing beyond its settings.

## Installation

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/vednolacni/ks-kitchen-view` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Once the plugin is listed in [Community Plugins](https://kiosksatellite.com/plugins/), this section gets the **Install in Kiosk Satellite** button:

```markdown
[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=vednolacni/ks-kitchen-view)
```

## Development

The plugin is built from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins). `sdk/` and `tools/` are the template's, unchanged except for the test list in `tools/test.py`.

| Path | What it holds |
| --- | --- |
| `src/io/github/vednolacni/kitchen/` | `KitchenViewPlugin` is the entry class. `Kitchen` holds the plugin's logic without Android views, `PanelView` and `AndroidUi` hold the views. |
| `tests/io/github/vednolacni/kitchen/` | Tests that run without a device. |

### Build

With Python 3, JDK 17 or newer and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

Without a local Android SDK, push to GitHub. The **Build** workflow runs the tests and attaches the ZIP to the run as an artifact.

Install the ZIP from `dist/` with **Plugin Manager > Developer Tools > Install from ZIP** on the kiosk or in Remote Admin.

### Release

Publish a GitHub release with a tag such as `v0.1.0`. The **Release** workflow builds it and attaches the manifest, the ZIP and its checksum.

## License

[Apache-2.0](LICENSE). Not affiliated with Kiosk Satellite or any recipe app.
