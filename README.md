# newznab-kotlin

A Kotlin client for Newznab indexers (Newznab, NNTmux, nZEDb, NZBHydra and Prowlarr) and Torznab indexers (Prowlarr,
Jackett), with a DSL for searches. Built on Ktor's client and coroutines; it runs on the JVM and on Android.

- Caps, searches (`search`, `tvsearch`, `movie`) and NZB downloads.
- Search every indexer at once, each with the query its caps support, and get each indexer's results as they arrive.
- Caps and answers are cached. An indexer that hits its API limit is left alone for as long as its `Retry-After` says.
- Torznab feeds are read by their torrents' v1 info hashes. Tracker links and passkeys are never kept.
- API keys never appear in logs, exception messages or `toString()`. A stored download link holds a placeholder in
  place of the key.

## Installation

Via [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.skjaere:newznab-kotlin:v0.1.0")
    // Plus a Ktor client engine, unless your app already has one:
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
}
```

The library brings no HTTP engine of its own. It uses the `HttpClient` you give it, or else builds one on whichever Ktor
engine is on the classpath.

## Usage

```kotlin
val newznab = Newznab {
    userAgent = "my-app/1.0"
    httpClient = myHttpClient       // optional: shares its engine
    timeouts {
        newznab = 15.seconds
        torznab = 25.seconds        // Torznab indexers ask trackers, which are slow
        download = 30.seconds
    }
    capsTtl = 24.hours
    resultTtl = 15.minutes          // how long searchAll reuses an answer
}

val geek = indexer("geek") {
    url = "https://api.nzbgeek.info"
    apiKey = System.getenv("NZBGEEK_API_KEY")
}
```

### Searching one indexer

```kotlin
val page = newznab.search(geek) {
    tv {
        tvdb = 81189
        season = 1
        episode = 2
    }
    categories(Categories.TV_HD, Categories.TV_UHD)
    limit = 100
}

newznab.search(geek) { movie { imdb = "tt0133093" } }
newznab.search(geek) { text("Harbor Lights") }
newznab.search(geek) { tv { tvdb = 71256; airDate = LocalDate.of(2026, 3, 9) } }  // a daily show
```

`search` sends exactly one request, as asked, and never caches it.

### Searching every indexer

```kotlin
newznab.searchAll(indexers) {
    // Runs once per indexer, with its caps.
    when {
        caps.supports(SearchType.Tv, "tvdbid") -> tv { tvdb = 81189; season = 1; episode = 2 }
        caps.supports(SearchType.Search) -> text("Breaking Bad S01E02")
        else -> skip("No TV search")
    }
}.collect { outcome ->
    when (outcome) {
        is IndexerOutcome.Results -> show(outcome.indexer, outcome.page.items)
        is IndexerOutcome.Failed -> warn(outcome.indexer, outcome.error)
        is IndexerOutcome.Skipped -> Unit
    }
}

// Or one query everywhere, skipping indexers whose caps don't list its search:
newznab.searchAll(indexers, searchQuery { movie { imdb = "tt0133093" } })
```

All enabled indexers are searched in parallel. One failing or timing out never affects the others. A search
cancelled while an indexer is still answering stops at once. The request itself still finishes into the cache, since
the indexer already counted it against your API limit.

### Downloading

```kotlin
val download = newznab.download(geek, page.items.first())
download.bytes      // the NZB
download.password   // the archive password the indexer sent (X-DNZB-Password, or in the file name), if any
```

To grab a release later without searching again, store `item.guid` and `item.downloadTemplate`. The template holds a
placeholder in place of the API key. Download with `newznab.download(indexer, guid, downloadTemplate)`.

### Errors

Every failure is a `NewznabException` whose `error` is one of `NewznabError`'s kinds: `Auth`, `ApiLimitReached` (with
`retryAfter`), `DownloadLimitReached`, `Disabled`, `Unsupported`, `NotFound`, `Server`, `BadResponse`, `Timeout` or
`Network`.

### For settings pages

- `UrlSecurity.of(url)` tells whether an indexer address would send the API key in cleartext over the internet.
- `IndexerEndpoint.of(url)` tells whether a key saved for one address may be reused for an edited one.
- `UserAgentRule.problem(value)` checks an indexer's own User-Agent before it is saved.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md).
