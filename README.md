# LogFlow — Yazılım Mimarisi Dönem Projesi

Günlük (log) dosyalarını **işlem hattı (pipeline)** mimarisiyle işleyen küçük bir Java uygulaması.
Bu sürüm: **v5 — Hata Yönetimi, Doğrulama ve Dead-Letter** (Artım 5).

Ana fikirler: hattın *topolojisi* **YAML dosyasında** (v4; yeniden derleme gerekmez) ve bozuk veri **sessizce yutulmaz**
(v5): her hatalı kayıt nedeniyle sınıflanır, aşamanın politikasına göre atlanır / dead-letter dosyasına yazılır /
çalışmayı durdurur ve çalışma sonunda hiçbir kaydın kaybolmadığı denetlenir.

Belgeler: [ARCHITECTURE.md](ARCHITECTURE.md) (yapılandırma şeması, kayıt sistemi, hata türleri,
v3 deneyi) · [CHANGELOG.md](CHANGELOG.md)

## Çalıştırma

Gereksinimler: JDK 17+ ve Maven 3.9+.

```bash
mvn -q package -DskipTests                  # jar üretir (sessiz çalışır; hata yoksa çıktı vermez)
./logflow --config configs/error-report.yaml
cat out/errors.txt
```

Windows: `logflow.bat --config configs\error-report.yaml`

### Kirli veri: v5 demosu

`data/access-dirty.log` kasıtlı bozulmuş 11 satırdır (3 geçerli; kesik, negatif bayt, 1970, 2099, geçersiz UTF-8, 100 KiB'lık satır, yorum).

```bash
./logflow --config configs/dirty-dead-letter.yaml   # 3 satır yazılır, 8 satır nedenle dead-letter'a gider
cat out/dirty-rejected.jsonl                        # her kayıt: aşama, hata türü, neden, satır no, özgün metin
./logflow --config configs/dirty-skip.yaml          # aynı dosya, hatalılar yalnızca atlanır ve sayılır
./logflow --config configs/dirty-fail-fast.yaml; echo "cikis kodu: $?"   # ilk hatalı kayıtta durur, kod 1
./logflow --config configs/dirty-dead-letter.yaml --dry-run             # her aşamanın hata politikasını gösterir
```

Özetin her satırı denetimdir: `okunan = yazılan + filtrelenen + atlanan + dead-letter + durduran`. Tutmuyorsa çıkış kodu 3.

### Aynı çalıştırılabilir dosya, üç farklı davranış

```bash
./logflow --config configs/error-report.yaml          # -> out/errors.txt              (3 satır)
./logflow --config configs/api-traffic.yaml           # -> out/api-traffic.txt         (3 satır)
./logflow --config configs/bandwidth-by-client.yaml   # -> out/bandwidth-by-client.txt (11 satır)
```

| Yapılandırma | Hat | Çıktı |
|---|---|---|
| `configs/error-report.yaml` | `parse → filter(status >= 400) → project(timestamp, clientIp, status, path)` | hata raporu |
| `configs/api-traffic.yaml` | `parse → filter(path startsWith /api) → project(timestamp, method, path, status)` | API trafiği |
| `configs/bandwidth-by-client.yaml` | `parse → filter(bytes > 0) → project(clientIp, bytes)` | istemci başına bant genişliği verisi |

`error-report.yaml`:

```yaml
pipeline:
  name: error-report
  source:
    type: file
    params: { path: data/access-small.log }
  stages:
    - type: parse
      params: { format: clf }
    - type: filter
      params: { field: status, op: ">=", value: 400 }
    - type: project
      params: { fields: [timestamp, clientIp, status, path] }
  sink:
    type: file
    params: { path: out/errors.txt }
```

Şemanın tamamı (tüm tür ve parametreler): [ARCHITECTURE.md](ARCHITECTURE.md), "Yapılandırma şeması".

### Komut satırı

```
logflow --config <yapilandirma.yaml> [--dry-run]
logflow <girdi> [--output dosya] [--min-status N] [--path-prefix önek]
        [--since an] [--until an] [--fields a,b,c]          (v3 kipi, hâlâ çalışır)
```

- `--dry-run`: yapılandırmayı yalnızca doğrular, **hiç veri işlemez, dosya yazmaz**:
  `Yapilandirma gecerli: error-report: file -> parse -> filter -> project -> file`
- `--config` başka seçeneklerle birlikte kullanılmaz.
- Çıkış kodu: `0` başarılı · `1` çalışma hatası (ör. çıktı yazılamadı) · `2` hatalı argüman **ya da yapılandırma hatası**.
- Atlanan hatalı satır sayısı `stderr`'e yazılır.

## Hata mesajı örnekleri

Yapılandırma hataları **tek satır okunmadan, tek dosya yazılmadan, yükleme sırasında** bildirilir
(fail fast, fail loudly), çıkış kodu 2. İletiler dosyayı, yeri ve çözümü söyler.
Aşağıdakilerin hepsi `configs/invalid/` altındaki gerçek dosyalardan alınmış gerçek çıktılardır;
her çalıştırmadan sonra `out/` dizini **oluşmaz**.

**Bilinmeyen aşama türü** (`unknown-stage.yaml`, yazım hatası `fliter`):

```
Yapilandirma hatasi: configs/invalid/unknown-stage.yaml: stages[1]: bilinmeyen asama (stage) turu 'fliter'. Bunu mu demek istediniz: 'filter'? Kullanilabilir turler: filter, parse, project
```

**Eksik parametre** (`missing-param.yaml`, `filter` için `field` yok):

```
Yapilandirma hatasi: configs/invalid/missing-param.yaml: stages[1] ('filter'): zorunlu parametre eksik: 'field'. Ornek: params: { field: status, op: ">=", value: 400 }
```

**Yanlış türde parametre** (`wrong-type.yaml`, sayı yerine metin):

```
Yapilandirma hatasi: configs/invalid/wrong-type.yaml: stages[1] ('filter'): 'value' parametresi tam sayi ('status' sayisal bir alan) olmali, bulundu: metin "dortyuz". Ornek: params: { field: status, op: ">=", value: 400 }
```

**Boş işlem hattı** (`empty-pipeline.yaml`, `stages: []`):

```
Yapilandirma hatasi: configs/invalid/empty-pipeline.yaml: pipeline.stages bos: en az bir asama gerekir. Ornek:
  stages:
    - type: parse
```

**Aşama tipleri uyuşmuyor** (`type-mismatch.yaml`, `project` unutulmuş):

```
Yapilandirma hatasi: configs/invalid/type-mismatch.yaml: sink ('file'): tip uyusmazligi: bu cikis ProjectedRecord bekliyor, ama stages[1] ('filter') LogRecord uretiyor. Araya su asamalardan biri gerekir: project.
```

**Kaynak dosya yok** (`missing-source-file.yaml`; kaynak dosya yükleme sırasında denetlenir, tek satır okunmadan):

```
Yapilandirma hatasi: configs/invalid/missing-source-file.yaml: source ('file'): kaynak dosya bulunamadi ya da okunamiyor: data/yok.log (calisma dizini: <calisma-dizini>)
```

**Bozuk YAML** (`bad-yaml.yaml`, kapanmayan `{`):

```
Yapilandirma hatasi: configs/invalid/bad-yaml.yaml: satir 2: ',' ya da '}' bekleniyordu: '{ type: file, params: { path: data/access-small.log }'
```

**Yapılandırma dosyası yok / hatalı argüman:**

```
$ ./logflow --config nofile.yaml
Yapilandirma hatasi: nofile.yaml: yapilandirma dosyasi bulunamadi ya da okunamiyor (NoSuchFileException)

$ ./logflow --config configs/error-report.yaml --min-status 400
Hata: --config baska seceneklerle ya da girdi dosyasiyla birlikte kullanilamaz (yalnizca --dry-run ile)
```

> **Yapılandırma hatası ≠ veri hatası.** Bozuk bir *günlük satırı* yapılandırma hatası değildir:
> Eski `file` kaynağıyla ve varsayılan `skip` politikasıyla bu satırlar atlanır ve **sayılır**
> (`atlanan (skip)  : 4`); nedenleri ve dead-letter aşağıda.

## Mimari

```
YAML ─▶ MiniYaml ─▶ PipelineConfig ─▶ PipelineLoader ─▶ PipelineBuilder ─▶ Pipeline.run()
                                          │  ▲
                            Source/Stage/SinkRegistry  (tür adı → fabrika, ServiceLoader ile keşfedilir)
```

| Parça | Sorumluluk |
|---|---|
| `config.PipelineLoader` | YAML'ı okur, türleri kayıt defterinde arar, parametreleri doğrular, tip uyumunu denetler, hattı kurar. |
| `config.StageRegistry` / `SourceRegistry` / `SinkRegistry` | Tür adı → fabrika. **`switch` yok**; `META-INF/services` dosyalarından `ServiceLoader` ile yüklenir. |
| `config.StageFactory` / `SourceFactory` / `SinkFactory` | Bir türün adı, giriş/çıkış tipi ve `create(Params)`. |
| `config.Params` | Tip denetimli parametre erişimi (`requireString/Int/StringList`), bilinmeyen parametre reddi. |
| `config.PipelineConfig` | Dosyanın yapısal modeli ve yapı doğrulaması. |
| `config.MiniYaml` | Bağımlılıksız küçük YAML ayrıştırıcı (alt küme; desteklenmeyeni satır numarasıyla reddeder). |
| `plugins.*Factory` | Yerleşik türler: `file` (kaynak), `parse`, `filter`, `project`, `file` / `console` (sink). |
| `PipelineBuilder`, `Pipeline`, aşamalar | v3'ten, **değişmedi**. Yükleyici `PipelineBuilder`'ın bir istemcisidir. |

**Yeni bir aşama türü eklemek:** `StageFactory` uygulayan bir sınıf yaz + sınıf adını
`src/main/resources/META-INF/services/edu.logflow.config.StageFactory` dosyasına bir satır olarak ekle.
Mevcut hiçbir dosya değişmez. Yeni bir **hat** için ise yalnızca yeni bir `.yaml` yazılır.

### Hata yönetimi (v5)

| Parça | Sorumluluk |
|---|---|
| `error.StageException` / `PipelineException` | Tek kaydın hatası (kurtarılabilir) / hattın devam edememesi |
| `error.ErrorPolicy` | `fail-fast`, `skip`, `dead-letter`, `retry(n)` (+ `fallback`, yalnızca sink) |
| `error.ErrorRouter` | Politikayı uygular, sayar, dead-letter'a yazar; aşamalar yalnızca fırlatır |
| `error.GuardedStage` / `GuardedSink` / `TrackingSource` | Mevcut bileşenleri sarar: çekirdek arayüzler değişmedi |
| `error.RunSummary` / `ManagedRun` | Mutabakat, durum ve çıkış kodu (0/1/2/3) |
| `RawLineSource` + `DecodeStage` | Bayt oku, katı UTF-8 çöz: geçersiz satır yalnızca kendi kaydını kaybettirir |
| `ValidationStage` | Bildirimli kurallar: zorunlu alan, IP, aralık, makul zaman damgası |

Ayrıntılı gerekçeler: [ARCHITECTURE.md](ARCHITECTURE.md), Artım 5.

### Bu hafta ne değişti? (v4 → v5)

- **Yeni:** `error/` paketi (14 sınıf), `RawLine`, `RawLineSource`, `DecodeStage`, `ValidationStage`, 3 fabrika,
  3 kirli-veri yapılandırması + 4 hatalı örnek, `data/access-dirty.log` / `access-empty.log`, yeni testler (`ChaosTest` dahil).
- **Değişen:** `ParserStage` (hatayı fırlatır, sayaç yok), `App` (`ManagedRun`, özet `stderr`'e, çıkış kodu 3),
  `PipelineConfig` / `PipelineLoader` / `LoadedPipeline` / `Params` / `StageFactory` (politika desteği).
- **Silinen:** `Summarizing` (özeti artık `RunSummary` verir).
- **Değişmeyen:** `PipelineBuilder`, `Pipeline`, `Stage`, `Source`, `Sink`, `Emitter`, filtre ve projeksiyon aşamaları.

### Önceki hafta (v3 → v4)

- **Yeni:** `config/` paketi (yükleyici, kayıt defterleri, fabrika arayüzleri, `Params`, `PipelineConfig`,
  `MiniYaml`, `ConfigException`, `LoadedPipeline`), `plugins/` paketi (6 yerleşik fabrika),
  `Summarizing`, `META-INF/services` dosyaları, `configs/` (3 çalışan + 7 hatalı örnek), yeni testler.
- **Değişen mevcut dosyalar:** `App` (`--config` kipi), `CliOptions` (`--config`, `--dry-run`),
  `FileSink` (eksik üst dizinleri oluşturur: `out/errors.txt`), `ParserStage` (`Summarizing` uygular: özet
  satırını yükleyici toplayabilsin), `pom.xml` (sürüm), `.gitignore` (`out/`).
- **Değişmeyen:** `PipelineBuilder`, `Pipeline`, `Sink`, `Stage`, `Emitter`, `Source`, `FileLineSource`,
  `PredicateFilterStage`, `ProjectionStage`, `ProjectedRecord`, `LogRecord`, `ConsoleSink`, `Main`.
  Ayrıntı: [CHANGELOG.md](CHANGELOG.md).

### Tasarım kararları

- **Tanım ≠ gerçekleştirim.** YAML "ne"yi söyler; Java sınıfları "nasıl"ı bilir; aşamalar YAML'dan habersizdir.
- **Tip denetimi yüklemede.** v3'te derleyici denetliyordu; topoloji dosyaya taşınınca aynı kuralı
  yükleyici çalışma başlamadan uygular.
- **Sessizce yok sayma yok.** Bilinmeyen anahtar, bilinmeyen parametre, yinelenen tür kaydı hata verir.
- **Bilinen sınırlamalar:** topoloji doğrusal; "bant genişliği" yapılandırması veri çıkarımı yapar
  (istemci başına *toplam* için durumlu aşama gerekir).

## Testler

JUnit 5, `mvn test`. Kapsama raporu: `target/site/jacoco/index.html`.

- `config/PipelineLoaderTest` — geçerli yapılandırma, bilinmeyen aşama türü, eksik parametre, yanlış tipte
  parametre, boş hat (istenen 5 durum) + tip uyuşmazlığı, yazım hatası, eksik dosya, bozuk YAML.
- `config/RegistryTest` — `ServiceLoader` keşfi, mevcut dosyalara dokunmadan yeni tür ekleme, çift kayıt reddi.
- `config/MiniYamlTest` — desteklenen YAML alt kümesi ve satır numaralı hatalar.
- `ConfigIntegrationTest` — gerçek `configs/*.yaml` dosyalarıyla uçtan uca; **aynı kodla üç yapılandırma
  üç farklı çıktı** verir; `configs/invalid/` altındaki tüm örneklerin hepsi çıkış kodu 2 verir ve dosya yazmaz.
- `error/*Test` — politikalar, yönlendirme (alt akış hatası yukarıya yazılmaz), `retry(n)` + `fallback`, `ManagedRun`
  (durum, çıkış kodu, **sessiz kayıp = MISMATCH**), JSONL biçimi.
- `RawLineSourceTest`, `DecodeStageTest`, `ValidationStageTest` — bayt okuma (CRLF, boş dosya, boyut sınırı), katı UTF-8, kurallar.
- `ChaosTest` — gerçek bozuk dosyada: çökme yok, 3 geçerli kayıt yazılır, 8 kayıt nedenle dead-letter'a gider, mutabakat
  tutar; fail-fast kod 1 verir; boş dosya temiz işlenir; eski `file` kaynağı aynı dosyada çöker (karşılaştırma).
- v3/v4 testleri (aşamalar, `PipelineBuilder`, `FileSink`, `IntegrationTest`...) duruyor; yalnızca sayaç kontrolleri yeni özete uyarlandı.

## Dizin yapısı

```
configs/                     error-report, api-traffic, bandwidth-by-client, dirty-dead-letter, dirty-skip, dirty-fail-fast
configs/invalid/             11 hatalı örnek (7 v4 + 4 v5)
data/access-small.log        örnek günlük (14 geçerli + 4 bozuk satır)
data/access-dirty.log        kasıtlı bozulmuş günlük (11 satır; bkz. data/README.txt); access-empty.log: boş dosya
src/main/java/edu/logflow/   çekirdek: Source, Stage, Sink, Emitter, Pipeline, PipelineBuilder, LogRecord,
                             ParserStage, PredicateFilterStage, ProjectionStage, FileSink, ConsoleSink, App ...
src/main/java/edu/logflow/error/     hata modeli, politikalar, yönlendirici, özet, ManagedRun
src/main/java/edu/logflow/config/    yükleyici, kayıt defterleri, fabrika arayüzleri, MiniYaml
src/main/java/edu/logflow/plugins/   yerleşik fabrikalar
src/main/resources/META-INF/services/ tür kayıtları (ServiceLoader)
ARCHITECTURE.md, CHANGELOG.md, README.md
logflow, logflow.bat         başlatıcı betikler
```
