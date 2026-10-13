# LogFlow — Yazılım Mimarisi Dönem Projesi

Günlük (log) dosyalarını **işlem hattı (pipeline)** mimarisiyle işleyen küçük bir Java uygulaması.
Bu sürüm: **v3 — Bileşim: Filtreleme, Projeksiyon ve Pipeline Builder** (Artım 3).
Mimari kararlar ve **aşama sırası deneyi**: [ARCHITECTURE.md](ARCHITECTURE.md).

## Çalıştırma

Gereksinimler: JDK 17+ ve Maven 3.9+.

```bash
mvn -q package                 # testleri çalıştırır, jar üretir, kapsama raporu oluşturur
./logflow data/access-small.log --min-status 400 --path-prefix /api --fields timestamp,clientIp,status
```

Windows: `logflow.bat data\access-small.log ...`

### Komut satırı

```
logflow <girdi> [--output dosya] [--min-status N] [--path-prefix önek]
                [--since an] [--until an] [--fields a,b,c]
```

| Seçenek | Anlamı |
|---|---|
| `--min-status N` | yalnızca `status >= N` |
| `--path-prefix P` | yalnızca `path` `P` ile başlıyorsa |
| `--since T` / `--until T` | `T1 <= zaman < T2` (ISO, ör. `2023-10-10T11:00:00Z`) |
| `--fields a,b,c` | yalnızca bu alanlar (varsayılan hepsi) — veri minimizasyonu |
| `--output F` | sonucu dosyaya yazar (yoksa ekrana yazar) |

Geçerli alanlar: `timestamp, clientIp, method, path, status, bytes, userAgent, query`.
Verilmeyen filtre hatta eklenmez. Atlanan hatalı satır sayısı `stderr`'e yazılır. Çıkış kodu:
0 başarılı, 1 çalışma hatası, 2 hatalı argüman.

Örnek çıktı (`--min-status 400 --fields timestamp,clientIp,status,path`):

```
timestamp=2023-10-10T10:56:01Z	clientIp=10.0.0.5	status=401	path=/api/login
timestamp=2023-10-10T10:57:30Z	clientIp=172.16.4.2	status=404	path=/missing/page
timestamp=2023-10-10T11:00:00Z	clientIp=203.0.113.7	status=500	path=/api/items/42
```

## Mimari

```
FileLineSource ─String─▶ ParserStage ─LogRecord─▶ PredicateFilterStage* ─▶ ProjectionStage ─ProjectedRecord─▶ FileSink
```

| Parça | Sorumluluk |
|---|---|
| `Source<T>` / `FileLineSource` | Öğeleri üretir. Girdi dosyasına dokunan **tek** yer. |
| `Stage<I,O>` | Bir girdiyi işler, çıktıları `Emitter<O>`'a yayar (0, 1 ya da çok çıktı). |
| `Sink<T>` | Son durak. `open()` / `accept()` / `close()` yaşam döngüsü. |
| `PipelineBuilder` | Akıcı, tip güvenli kurucu: `from(src).via(stage)...to(sink)`. |
| `Pipeline` | Kurulmuş hat; `run()` sink'i açar, akıtır, **her durumda** kapatır. |
| `LogRecord` | Değişmez alan modeli (v2). |
| `ParserStage` | CLF satırı → `LogRecord`; hatalı satırı atlar ve sayar (v2). |
| `PredicateFilterStage` | **Yeni.** `Predicate<LogRecord>` doğruysa iletir. |
| `ProjectionStage` | **Yeni.** Kaydı seçilen alanlara indirger (`ProjectedRecord`). |
| `FileSink` | **Yeni.** Dosyaya yazar; `close()`'da boşaltıp kapatır. |
| `ConsoleSink` | v2'den; `LogRecord`'u tek satırda yazar (v3 komut satırında kullanılmıyor). |
| `CliOptions` / `App` | Argümanları ayrıştırır / aşamaları seçeneklere göre bağlar. |
| `OrderExperiment` | Aşama sırası deneyi (bkz. ARCHITECTURE.md). |

### Bu hafta ne değişti? (v2 → v3)

- **Yeni dosyalar:** `PredicateFilterStage`, `ProjectionStage`, `ProjectedRecord`, `FileSink`,
  `PipelineBuilder`, `CliOptions`, `App`, `OrderExperiment`, `ARCHITECTURE.md` ve testler.
- **Değişen mevcut dosyalar:** `Sink` (yaşam döngüsü kancaları eklendi, varsayılan gövdeleri boş),
  `Pipeline` (kurucu mantık `PipelineBuilder`'a taşındı), `LineSource` → `FileLineSource`
  (yeniden adlandırma), `Main` (artık `App`'e devrediyor).
- **Değişmeyen:** `Emitter`, `Stage`, `Source`, `LogRecord`, `ParserStage`, `ConsoleSink`.
  Ayrıntı: [CHANGELOG.md](CHANGELOG.md).

### Tasarım kararları

- **Aşamalar tek sorumluluklu ve ~40 kod satırının altında** (23 / 39 / 35).
- **Filtre ve projeksiyon aynı mimari öğedir** (ikisi de `Stage`); fark yalnızca ne yaydıklarıdır.
- **Önce filtre, sonra projeksiyon.** 4,3 kat daha az iş yapar ve projeksiyonun attığı alana
  filtrenin bağımlı olması sorununu ortadan kaldırır (ARCHITECTURE.md, bölüm 7.3).
- **Sink'in `close()`'u süs değil:** kapatılmayan `FileSink` arabellekteki veriyi sessizce kaybeder
  (`FileSinkTest` bunu gösterir); bu yüzden hat `close()`'u hata olsa bile çağırır.
- **Bilinen sınırlama:** hatalı satırlar hâlâ yalnızca atlanır ve sayılır; nedenleri ve ayrı
  kanal 5. haftada gelecek.

## Testler

JUnit 5, `mvn test`. Kapsama raporu: `target/site/jacoco/index.html`.

- `PredicateFilterStageTest`, `ProjectionStageTest`, `FileSinkTest` — her yeni aşama için ayrı testler.
- `PipelineBuilderTest` — tip güvenli birleştirme, `open → accept* → close` sırası, hata durumunda `close()`.
- `CliOptionsTest` — argüman ayrıştırma ve hatalı girdi.
- `IntegrationTest` — küçük fixture üzerinde 3 aşamalı hat çalışır ve **çıktı dosyasının içeriği** doğrulanır;
  ayrıca komut satırı yolu (`App.run`) uçtan uca denenir.
- v2 testleri (`ParserStageTest`, `LogRecordTest`, `ConsoleSinkTest`, `PipelineTest`) duruyor.

Aşama testleri dosya sistemine dokunmaz: `CollectingEmitter` aşamanın yaydığı her şeyi toplar.
Yalnızca `FileSink` ve uçtan uca testler geçici dizin (`@TempDir`) kullanır.

## Deneyi tekrarlamak

```bash
mvn -q package -DskipTests
java -Xmx2g -cp target/classes edu.logflow.OrderExperiment
```

## Dizin yapısı

```
data/access-small.log        örnek günlük
src/main/java/edu/logflow/   Emitter, Stage, Sink, Source, FileLineSource, Pipeline, PipelineBuilder,
                             LogRecord, ParserStage, PredicateFilterStage, ProjectionStage,
                             ProjectedRecord, FileSink, ConsoleSink, CliOptions, App, Main,
                             OrderExperiment
src/test/java/edu/logflow/   CollectingEmitter ve her sınıfın testi, IntegrationTest
ARCHITECTURE.md, CHANGELOG.md, README.md
logflow, logflow.bat         başlatıcı betikler
```
