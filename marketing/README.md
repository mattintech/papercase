# Papercase store graphics

Google Play feature graphic and screenshots, built as a web page so headlines and layout are easy to change.

Open `index.html` in Chrome (straight from disk is fine) and:

- **Click any headline or subtitle to edit it.** Edits are kept in your browser; **Reset edits** clears them.
- **Download** saves one image (a spanning pair saves both halves). **Download set** and **Download everything** make a zip.

Downloads are at Google Play's sizes. Screenshots and the feature graphic are JPEGs (Play rejects PNGs with an alpha channel); the icon is a PNG:

| Set | Size | Play Console field |
|---|---|---|
| App icon | 512 × 512 PNG | App icon (Play rounds the corners itself) |
| Feature graphic | 1024 × 500 | Feature graphic |
| Phone | 1080 × 1920 (9:16) | Phone screenshots |
| Tablet | 1440 × 2560 (9:16) | 7-inch and 10-inch tablet screenshots |

Shots `02` and `03` in each set are a pair: one device crosses the seam between them, so upload them next to each other, in order.

## Changing things

| File | What's there |
|---|---|
| `app.js` | The shot list (`SETS`): headlines, which screen each shot shows, floating cards; device frames and layouts |
| `app.css` | Colours, backgrounds and type |
| `screens/` | The app screenshots (taken on an emulator with made-up sample documents) and the specimen ID card art |
| `screens.js` | Generated from `screens/` so the page works from `file://`. After changing a screenshot run `python3 build-screens.py` (needs Pillow) |

The status bar on each screenshot is painted over with a clean one (12:00, full signal and battery), so screenshots from different devices match. All documents shown are fake specimens.

Rendering uses [html-to-image](https://github.com/bubkoo/html-to-image) and [JSZip](https://stuk.github.io/jszip/) from a CDN, so you need to be online.
