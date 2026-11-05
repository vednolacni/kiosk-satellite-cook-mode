# Cook Mode design

## What it is for

A recipe app's own web page is a poor thing to cook from on a wall tablet. It shows everything at once in small type, sleeps while the pot boils, and knows nothing of the tablet. Cook Mode keeps the recipe app as the server and does the cooking itself: its own screens, timers, sound, voice and screen hold, in a plugin that talks to the app's REST API.

## What a real cook mode needs

Each row is something cooks run into at the stove, and how the plugin answers it.

| The need | In the plugin |
| --- | --- |
| Find today's meal without searching | Today's meals first, one tap away, and **Cook today's meal** without a tap |
| Cook with no meal plan at all | Every recipe with search on the same page |
| Search on a kiosk with no keyboard | The plugin's own keys, with Č, Š and Ž |
| Read it from across the kitchen | One step at a time, the text filling the room the step leaves |
| Know where you are | Step count, a progress line, the steps around it faded, "Ready around 18:30" |
| Move with dirty hands | Big fixed Next button, swipes in any direction, gestures and claps through KS |
| One press that lands twice | Steps move at most once in 350 ms, and a timer chip starts once. For 350 ms nothing takes a touch after a page, a question, the timer sheet or its rows or the strip have come or gone, or after the keys have gone. Keys coming up start no such wait, and neither does an alarm, which takes the first touch once any wait already running has ended |
| Enough for four, not two | Servings rescale ingredient amounts and the amounts inside the steps |
| A number typed into a step | It does not scale, so the step says for how many servings its words were written |
| Not be surprised at step 3 | Chips before starting: overnight, long waits, the oven's temperature |
| Not forget an ingredient | Tick-off circles, with the step's own ingredients under it and the whole list beside it |
| Timers without typing | Durations in the text are chips to tap, plus presets |
| Several things at once | Up to six timers, named by their step, with pause and plus or minus a minute |
| Hear the timer | The alarm tone or beeps, on the alarm or media stream, a test button, a warning when the volume is at zero, and each new ring gets its full time of sound |
| A false clap | A step, read or stop command only silences a ringing timer. It stays on show until a second command or a touch. One more minute starts it again for a minute, and it rings again after that |
| A little longer | "+1 min" on the alarm, and as a command |
| Not touch the screen to read | Read-aloud, on request or by itself at each step, with no card over the step |
| A screen that stays on | The screensaver held back while a recipe is up, an idle limit for a forgotten one |
| Leave and come back | The place and the timers stay until End cooking, and a small card shows them meanwhile |
| The kiosk restarts mid-braise | The step comes back from a setting, unless the kiosk stayed off for about 12 hours, and the timers however long it was off. A timer that ended less than half an hour ago rings, also one that was ringing, and the plugin's page mentions an older one. If the server is not up yet, a card and the first page hold the place, and its recipe is asked for again every minute |
| Not lose a place by accident | Opening another recipe, or ending one past its first step, asks first, and the first one comes back if the other cannot be read |
| Close the last page | The meal is ready, so that ends cooking. Only a timer's card stays |
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
- **`Screen`** classes stay while their page does and change in place. Rebuilding under a finger would swallow its tap, so only the words change when a step does, and a held plus button keeps working. The first page stays across visits, so coming back is instant and the lists are where they were. More of the same recipes only adds rows, and an answer with nothing new changes nothing.
- **`RecipeSource`** is the only thing that knows a recipe app. Everything past it uses `Recipe`, `Planned` and `Summary`.
- **`Snapshot`** is what a restart must not lose, in one setting of at most 512 characters. Labels shorten, then the furthest timers go. It names its device, because fleet sync can copy it to another kiosk. It is a `secret` setting, because a plain one would count as a change to a synced plugin at every step. That holds unless a profile includes Plugin secrets, where the leader's value also replaces a follower's. It is written again every half hour while a recipe is being cooked, so its time says when the kiosk was last up, not when a step last changed.
- Everything except the views runs on a plain JVM, so most tests need no device. `tools/test.py` still needs an installed Android platform to compile against. The views have their own tests under Robolectric.

### Kiosk Satellite's rules, and what they shaped

| The rule | What follows |
| --- | --- |
| Creating an overlay takes seconds on a slow tablet | One long-lived full-screen overlay whose pages swap, and a small dock for while it is closed |
| A resized wrapped overlay can show stale frames | The dock keeps one size |
| An exception in a listener, in `onDraw` or `onTouchEvent`, or on a plugin thread ends the app | Listeners and posted view tasks go through `Parts.guard`, the controller's posted tasks catch what the host throws, and network tasks catch everything. `guard` catches only `RuntimeException`: going on after running out of memory halfway through a page would leave half a page |
| An exception in a callback disables the plugin | After the start, `CookModePlugin` turns one into a status |
| A callback has three seconds | Network work runs on the plugin's own threads |
| Host calls throw once the session ends, and commands throw with 8 pending, which a slow Home Assistant can reach | Tasks on the main thread catch that, and a command nothing waits for is sent best effort, so a ring never depends on one |
| Android refuses to draw a bitmap over 100 MB, with an exception in the draw pass | `Images` samples a picture by its pixels as well as its width |
| Home Assistant turns a state over 255 characters into `unknown` | Text sensors stop at 255 |
| Before 2026.11.1 a kiosk refuses to announce while it announces, with no way to stop it | The newest text waits and is tried every 1.5 seconds for two minutes, and the plugin's page says if it gives up. From 2026.11.1 the stop action and taking over do the job |
| Plugin views sit under the screensaver, and plugins cannot pass `hold` to a page | `Awake` reports activity before each deadline, and leaves alone a screensaver that was asked for |
| A tablet with no battery can start with its clock far behind, and learn the time later | A timer started here also counts on a clock nobody can set, so setting the wall clock does not move it. One from before a restart waits for the clock to be set, and then rings or is only mentioned, by how long ago it ended |
| Back closes an overlay | The pages have their own back buttons, and reopening returns to the same place. On the last page that ends cooking |
| Several fast taps anywhere are Kiosk Mode's exit gesture | Plus and minus repeat while held instead of asking for fast taps. Typing on the keys is the one exception, which the README notes |
| A kiosk may have no keyboard, and one resizes the overlay | The search draws its own keys |
| A `Context` exists only in `OverlayFactory.create` | Beeps need none, and the alarm tone and the tablet voice use the one the first view brought |
| `assist_satellite.announce` shows a card, and the kiosk's action can leave it out | Home Assistant's voice goes through `esphome.<node>_announce` with `audio_only` |
| The SDK has no timers, sound or speech | The plugin has its own |

### The screen hold

`Awake` is held while the main view shows anything but the first page, while a timer rings, or while a KS page opened from here may be up. It calls `postponeScreensaver` 15 seconds before each deadline KS announces, or halfway to a near one, with a beat every 30 seconds for a deadline it has not heard of. The margin is wide because the command crosses a main thread that a slow view can stall.

`postponeScreensaver` also dismisses a screensaver that is up, so during one the beats wait: one that starts during a hold was asked for, by a schedule, a gesture or Now Playing. The exception is one that starts within 10 seconds of the deadline, which means a beat came too late. It gets one postpone, and a second such start within two minutes is left alone.

With nobody touching the tablet for **Let the screen rest after**, the hold ends, also for a ringing timer, whose sound has stopped long before. A new ring starts that wait over, and the next touch or action holds again.

### Commands

A command comes from a gesture, a clap, a key or Home Assistant, none of which the cook can see land. So:

1. The same command within 700 ms is an echo and is dropped.
2. While a timer rings, a step, read or stop command only silences it. Another one clears it. A touch on the alarm clears it at once. One more minute starts it again for a minute. The panel commands, Cook today's meal and End cooking do what they say and leave the ring alone.
3. With the panel closed or under a screensaver, a step command only brings it into view, so nothing moves unseen. Toggle shows it there too, rather than closing what nobody sees.
4. Next means the obvious thing for the page: today's meal, start cooking, or the next step.

### Tandoor, as verified in its source

- `Authorization: Bearer <tda_...>`. A token needs the scope `read write`, and 2.5.0 and 2.5.1 also check `mealplan`.
- 401 and 403 on a read mean the token, except a 403 on one recipe, which means it is private and not shared with the token's user, while the meal plan lists it all the same. A token gone bad answers the same there, so the list, which any good token can read, says which it was. A 404 in JSON means a recipe is gone, and any other 404 comes from something in front of Tandoor, such as a proxy while it starts. A 403 on a write means a token that can only read. A 400 with an HTML body means `ALLOWED_HOSTS`, empty by default from 2.6.1, and with a JSON body whatever it says. Tandoor 2.x answers unknown paths with its page and a 200, so an HTML body is never treated as data, and paths keep their trailing slash.
- `GET /api/meal-plan/?from_date&to_date&page_size=200`. Both bounds filter on `to_date`. 1.5 returns an array, and 2.x pages it.
- `GET /api/recipe/?query&page&page_size&sort_order=-lastviewed` and `GET /api/recipe/{id}/`.
- `instructions_markdown` is HTML whose amounts are `<scalable-number>` and `<plural-name>` tags for the client to scale. 2.6.7 sends raw Markdown, and 2.6.7 and 2.6.8 escape `plural-name` as text. `Instructions` handles all of them.
- `POST /api/cook-log/ {recipe, servings}` takes decimal servings only from 2.7, so older servers get whole ones. `POST /api/view-log/ {recipe}`.
- `/media/` is public. `GET /api/server-settings/current/` is public and tells the version from 2.0.
- A step's time is in minutes, a header ingredient has its text in `note`, and `step_recipe_data` holds a sub-recipe. A step with nothing else than that is no page of its own, as on Tandoor's own page.

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

1. **Try it on the tablets.** The views run under Robolectric, not on a panel. These need a real device:
   - how long `main` and `dock` take to create on the ThinkSmart
   - the heartbeat through several screensaver timeouts
   - a timer ringing with the view open, closed, under the screensaver and with the screen off
   - the alarm's volume and streams
   - the stop word during an alarm
   - the tablet voice on Android 11 or newer, since KS's manifest has no `TTS_SERVICE` query
   - how soon the announce action answers
   - gestures and their toasts
   - scrolling the recipe grid on a weak panel
   - a three-hour soak for memory
2. **Two recipes at once.** Opening another recipe asks first and then forgets the first one's place, though its timers run on. A main and a side dish deserve a list of recipes in progress.
3. **A question before the screen rests.** "Still cooking?" a minute before the idle limit, so the screensaver does not surprise anyone.
4. **An alarm over everything.** With the panel closed the dock turns red over the dashboard and the screensaver, and a black screensaver hides it until the ring ends that screensaver. A full-screen alarm overlay would be harder to miss, at the cost of seconds to build it on a slow tablet.
5. **Smarter choice of today's meal.** By the meal type's name when it has no time, and leading with tomorrow in the evening, so there is time to thaw or soak.
6. **Pages instead of a scrolling grid**, if scrolling proves slow on a weak panel: six big cards and arrows, which also suits wet hands.
7. **Bluetooth page turners** through `device.key`.
8. **Mealie**, as the second source.
9. **Opening today's recipes before they are asked for**, so they open at once and survive Wi-Fi dropping.
10. **A shopping list** from a recipe or the week's plan, to Tandoor's list and Home Assistant's `todo`.
11. **Pictures in steps.** Tandoor steps can carry their own file.
12. **Sub-recipes** as their own section, instead of only their ingredients.
13. **Timers through Kiosk Satellite**, if it ever exposes them, so voice timers and these share one list.
14. **Requests upstream:** a session-scoped screensaver hold for plugins, an event when a link page closes, documented keyboard behavior in overlays, and a sound and speech API, so the plugin need not bring its own.
