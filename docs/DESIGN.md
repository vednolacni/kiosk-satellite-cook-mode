# Cook Mode design

## What it is for

A recipe app's own web page is a poor thing to cook from on a wall tablet. It shows everything at once in small type, sleeps while the pot boils, and knows nothing of the tablet. Cook Mode keeps the recipe app as the server and does the cooking itself: its own screens, timers, sound, voice and screen hold, in a plugin that talks to the app's REST API.

## What a real cook mode needs

Each row is something cooks run into at the stove, and how the plugin answers it.

| The need | In the plugin |
| --- | --- |
| Find today's meal without searching | Today's meals first, one tap away, and **Cook today's meal** without a tap |
| Cook with no meal plan at all | Every recipe with search on the same page |
| Read it from across the kitchen | One step at a time, in three sizes of text |
| Know where you are | Step count, a progress line, the steps around it faded, "Ready around 18:30" |
| Move with dirty hands | Big fixed Next button, swipes in any direction, gestures and claps through KS |
| Enough for four, not two | Servings rescale ingredient amounts and the amounts inside the steps |
| Not forget an ingredient | Tick-off circles, with the step's own ingredients under it and the whole list beside it |
| Timers without typing | Durations in the text are chips to tap, plus presets |
| Several things at once | Up to six timers, named by their step, with pause and plus or minus a minute |
| Hear the timer | The alarm tone on the alarm stream, with a beep as a fallback, stopped by a touch anywhere or the stop word |
| A little longer | "+1 min" on the alarm |
| Not touch the screen to read | Read-aloud, on request or by itself at each step |
| A screen that stays on | The screensaver held back while a recipe is up, an idle limit for a forgotten one |
| Leave and come back | The place and the timers stay until End cooking, and a small card shows them meanwhile |
| Imported recipes with one huge step | Long steps split into paragraphs |
| Record what was cooked | The cook log, asked, always or never |

Worth adding later is in [What comes next](#what-comes-next).

## How it fits together

```
 KS callbacks       taps, swipes         answers from the server
 (plugin worker)    (main thread)        (network threads)
       \                 |                   /
        v                v                  v
     +------------------------------------------+
     | Cook: the controller. One lock, no       |
     | Android, no network while it is held.    |
     +------------------------------------------+
        |              |                 |
        v              v                 v
   Timers, Awake,   Ui: AndroidUi,    RecipeSource:
   Pages, StepText  CookView, screens TandoorSource
```

- **`Cook`** owns all state. Every way in holds its lock, and nothing holds it across a network call. Answers carry a generation number, so a late answer to an old question is dropped.
- **`Model`** is what the views show, worked out by the controller, so the views only lay it out.
- **`Screen`** classes stay while their page does and change in place. Rebuilding under a finger would swallow its tap, so only the words change when a step does, and a held plus button keeps working.
- **`RecipeSource`** is the only thing that knows a recipe app. Everything past it uses `Recipe`, `Planned` and `Summary`.
- Everything except the views runs on a plain JVM, so the tests need no device and no Android SDK.

### Kiosk Satellite's rules, and what they shaped

| The rule | What follows |
| --- | --- |
| Creating an overlay takes seconds on a slow tablet | One long-lived full-screen overlay whose pages swap, and a small dock for while it is closed |
| A resized wrapped overlay can show stale frames | The dock keeps one size |
| An exception in a listener or a plugin thread ends the app | Every listener and posted task goes through `Parts.guard`, and network tasks catch what they might throw |
| A callback has three seconds | Network work runs on the plugin's own threads |
| Host calls throw once the session ends | Tasks on the main thread catch that |
| Plugin views sit under the screensaver, and plugins cannot pass `hold` to a page | `Awake` reports activity just before each deadline, and leaves alone a screensaver that was asked for |
| Back closes an overlay | The pages have their own back buttons, and reopening returns to the same place |
| Five to seven fast taps are Kiosk Mode's exit gesture | Plus and minus repeat while held instead of asking for fast taps |
| The keyboard resizes the overlay | The search field sits at the top, and a change of height alone does not rebuild the page |
| The SDK has no timers, sound or speech | The plugin has its own, on the application context from `OverlayFactory.create` |

### The screen hold

`Awake` is held while the main view shows anything but the first page, while a timer rings, or while a KS page opened from here may be up. It calls `postponeScreensaver` five seconds before each deadline KS announces, with a beat every 30 seconds for a deadline it has not heard of. `postponeScreensaver` also dismisses a screensaver that is up, so during one the beats wait: one that starts during a hold was asked for, by a schedule, a gesture or Now Playing. With nobody touching the tablet for **Let the screen rest after**, the hold ends. The next touch or action starts it again.

### Tandoor, as verified in its source

- `Authorization: Bearer <tda_...>`. A token needs the scope `read write`, and 2.5.0 and 2.5.1 also check `mealplan`.
- 401 and 403 mean the token. A 400 with an HTML body means `ALLOWED_HOSTS`, empty by default from 2.6.1. Tandoor 2.x answers unknown paths with its page and a 200, so an HTML body is never treated as data, and paths keep their trailing slash.
- `GET /api/meal-plan/?from_date&to_date&page_size=200`. Both bounds filter on `to_date`. 1.5 returns an array, and 2.x pages it.
- `GET /api/recipe/?query&page&page_size&sort_order=-lastviewed` and `GET /api/recipe/{id}/`.
- `instructions_markdown` is HTML whose amounts are `<scalable-number>` and `<plural-name>` tags for the client to scale. 2.6.7 sends raw Markdown, and 2.6.7 and 2.6.8 escape `plural-name` as text. `Instructions` handles all of them.
- `POST /api/cook-log/ {recipe, servings}` takes decimal servings only from 2.7, so older servers get whole ones. `POST /api/view-log/ {recipe}`.
- `/media/` is public. `GET /api/server-settings/current/` is public and tells the version from 2.0.
- A step's time is in minutes, a header ingredient has its text in `note`, and `step_recipe_data` holds a sub-recipe.

## Other recipe apps

A new app is one class implementing `RecipeSource`, one more choice in the settings and a test against its answers. Nothing else changes. What each offers, read from their API documentation and source:

| | Mealie | Norish | KitchenOwl | Grocy |
| --- | --- | --- | --- | --- |
| **Auth** | `Bearer` token | `x-api-key` | `Bearer` token | `GROCY-API-KEY` |
| **Meal plan** | `/api/households/mealplans?start_date&end_date&perPage=-1` | `/api/v1/planned-recipes/{today\|week\|month}` | `/api/household/{hid}/planner` | `/api/objects/meal_plan` |
| **Recipes** | `/api/recipes` | `POST /api/v1/recipes/search` | Per household | `/api/objects/recipes` |
| **A recipe** | `/api/recipes/{slugOrId}` | `/api/v1/recipes/{uuid}` | `/api/recipe/{id}` | `/api/objects/recipes/{id}` |
| **Steps** | `recipeInstructions`, each with text | Steps | One Markdown description | One HTML description |
| **Step format** | `MARKDOWN` | `MARKDOWN` | `MARKDOWN`, split by `Pages` | `PLAIN` after stripping tags, split by `Pages` |
| **Scaling** | `recipeScale` and ingredient quantities | Servings and quantities | Yields | Desired servings |
| **Step times** | None, so timers come from the words | Check | None | None |
| **Pictures** | Public: `/api/media/recipes/{id}/images/original.webp` | Need the key, so `imageHeaders` sends it | Check | Need the key |
| **Recording cooked** | Timeline events and last made | None: `cooked` throws `UNSUPPORTED` and the button hides | None | `consume` affects stock, so opt-in only |

Norish has rate limits, so its adapter should cache more. Grocy's `consume` changes stock, which goes further than a cook log and needs its own setting.

## What comes next

Roughly in order of how much they would help.

1. **Try it on the tablets.** The views have only been compiled. The keyboard in an overlay, the tablet voice on Android 11 or newer (KS's manifest has no `TTS_SERVICE` query, so it may fail there), the alarm volume and the swipes all need a real device.
2. **Mealie**, as the second source.
3. **A shopping list** from a recipe or the week's plan, to Tandoor's list and Home Assistant's `todo`.
4. **An offline copy** of the recipe being cooked and today's meals, so Wi-Fi dropping does not end dinner.
5. **Pictures in steps.** Tandoor steps can carry their own file.
6. **Sub-recipes** as their own section, instead of only their ingredients.
7. **Timers through Kiosk Satellite**, if it ever exposes them, so voice timers and these share one list.
8. **Requests upstream:** a session-scoped screensaver hold for plugins, an event when a link page closes, and a sound and speech API, so the plugin need not bring its own.
