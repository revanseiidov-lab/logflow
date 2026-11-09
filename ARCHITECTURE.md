# LogFlow Mimarisi

Bu belge üç artımı kapsar: **Artım 5** (hata yönetimi, doğrulama, dead-letter; en güncel), **Artım 4**
(yapılandırma odaklı birleştirme ve aşama kaydı) ve **Artım 3** (bileşim, filtreleme, projeksiyon; aşama sırası
deneyi). Önceki artımların bölümleri aşağıda aynen durur.

## Artım 5 — Hata Yönetimi, Doğrulama ve Dead-Letter

**Ana fikir:** v4'e kadar bozuk satır *sessizce* atlanıyordu (yalnızca bir sayı). Üretim hattında bu kabul edilemez:
bozuk veri **bilinmeli, sayılmalı, gerekirse saklanmalı** ve hiçbir kayıt **sessizce kaybolmamalı**. v5 bunu üç parçayla
sağlar: bir hata modeli, aşama başına bir politika ve çalışma sonunda bir **mutabakat** (reconciliation) denetimi.

```
raw-file ─RawLine─▶ decode ─String─▶ parse ─LogRecord─▶ validate ─▶ filter* ─▶ project ─▶ sink (file)
   │                  │                │                   │                                   │
   └─ okunan sayaç    └──────── hata ──┴───────────────────┘                                   └─ yazılan sayaç
                                       ▼
                                  ErrorRouter ──skip──▶ sayaç
                                       │──dead-letter──▶ JSONL dosyası (nedenle birlikte)
                                       └──fail-fast────▶ AbortRun ─▶ ManagedRun ─▶ çıkış kodu 1
```

### 1. Hata modeli: iki tür, iki sorumluluk

| Tür | Anlamı | Sonuç |
|---|---|---|
| `StageException` (+ `ErrorType`) | **Tek bir kayıt**, **tek aşamada** işlenemedi. Kurtarılabilir. | Politika uygulanır, hat devam eder. |
| `PipelineException` | Hat **devam edemez**: girdi okunamıyor, dead-letter yazılamıyor. | Çalışma durur (`FAILED`, çıkış kodu 1). |

Ayrım sorusu: *"Bu kaydı atlayıp devam etmek anlamlı mı?"* Evet → `StageException`. Hayır → `PipelineException`.
Dead-letter hedefinin yazılamaması bilerek ikinci gruptadır: kanıt sessizce kaybolacaksa "atla" seçeneği yoktur.

`ErrorType`, hatanın nerede/neden olduğunu söyleyen kapalı bir sözlüktür: `READ_ENCODING`, `READ_OVERSIZE`,
`PARSE_MALFORMED`, `PARSE_TIMESTAMP`, `PARSE_NUMBER`, `VALIDATION_REQUIRED`, `VALIDATION_IP`, `VALIDATION_RANGE`,
`VALIDATION_TIMESTAMP`, `SINK_IO`, `UNEXPECTED`. Özetler ve dead-letter kayıtları bu adları kullanır; metin ayrıştırarak
"şu hata mıydı?" diye bakmak gerekmez.

**Neden istisna (exception), dönüş değeri değil?** `Stage` arayüzü v1'den beri `void process(I, Emitter<O>)`.
`StageException` denetlenmeyen (unchecked) olduğundan arayüz, `PipelineBuilder`, `PredicateFilterStage`,
`ProjectionStage` **değişmedi**. Alternatif (`Result<T>` ya da `Emitter`'a ikinci bir hata kanalı) her aşamanın imzasını
bozardı.

### 2. Aşama fırlatır, yönlendirici karar verir

Bir aşama kaydın akıbetini bilmez: yalnızca `throw new StageException(tip, neden)` der. Akıbet **yapılandırmadadır**
(`on-error:`) ve `ErrorRouter` tarafından uygulanır. Böylece aynı `parse` aşaması bir hatta "dur", başka bir hatta
"dead-letter" olarak çalışır; aşama kodu değişmez. Bu, v4'teki "tanım ≠ gerçekleştirim" ilkesinin hata tarafıdır.

| Politika | Davranış | Ne zaman |
|---|---|---|
| `fail-fast` | `AbortRun` fırlatır, çalışma `ABORTED` biter | Veri bütünlüğü her şeyden önemli (ör. finansal kayıt) |
| `skip` | Kaydı atar ve **sayar** (tür ve aşama bazında) | Kayıp tolere edilir, ama bilinmelidir |
| `dead-letter` | Kaydı **nedeniyle birlikte** ikincil dosyaya yazar | Sonradan incelenecek/yeniden oynatılacak veri |
| `retry(n)` + `fallback` | Aşamayı n kez daha dener; tükenirse `fallback` politikası | **Yalnızca sink** (geçici G/Ç hatası) |

Varsayılanlar: aşamalar `skip` (v4 davranışı korunur ama artık sayılır), sink `fail-fast` (çıktı sessizce kaybolmaz).

**`retry` neden yalnızca sink'te?** Ayrıştırma ve doğrulama hataları *deterministiktir*: aynı satır her denemede aynı
hatayı verir, yeniden denemek yalnızca zaman harcar. Yazma hatası ise geçici olabilir (disk dolu, ağ). Yükleyici
`retry`'ı bir aşamada reddeder ve nedenini söyler (`configs/invalid/retry-on-stage.yaml`).

### 3. Dekoratörler: çekirdek değişmeden hata yönetimi

`GuardedStage`, `GuardedSink`, `TrackingSource` mevcut bileşeni **sarar** (decorator); `PipelineLoader` her bileşeni
kurarken sarar. İki ince nokta:

- **Alt akış hatası yukarıya yazılmaz.** Aşama A, `out.emit(x)` ile aşama B'yi çağırır; B'nin hatası çağrı yığınında A'nın
  içinden geçer. `GuardedStage` emit'i bir taşıyıcıyla sarar, böylece B'nin hatası A'nın hatası sayılmaz
  (`ErrorRoutingTest.failuresDownstreamAreNotAttributedToTheUpstreamStage`).
- **Beklenmeyen istisna da kayıt hatasıdır.** `NullPointerException` gibi bir bug tüm çalışmayı çökertmez; `UNEXPECTED`
  olarak aynı politikadan geçer ve dead-letter'da görünür.

Kural: bir aşama kaydı yaymadan **önce** fırlatmalıdır (yarım çıktı olmaz). `ParserStage` bunu sağlar.

### 4. Okuma hataları kayıt düzeyinde: `raw-file` + `decode`

v4'ün `file` kaynağı dosyayı katı UTF-8 ile satır satır okur. İlk geçersiz bayt dizisinde **tüm çalışma**
`MalformedInputException` ile durur: tek satır yüzünden milyonlarca satır kaybolur (`ChaosTest.legacyFileSourceStops...`
bunu gösterir). v5'te okuma ikiye bölündü:

- `raw-file` (`RawLineSource`): **baytları** satırlara böler. Kodlamayı çözmez. `maxLineBytes`'ı aşan satırı
  `oversize` işaretler ve belleğe yalnızca bir ön ekini alır (100 KiB'lık satır bellek tüketmez).
- `decode` (`DecodeStage`): **katı** UTF-8. Geçersiz diziyi sessizce `U+FFFD` yapıp veriyi bozmak yerine kaydı
  `READ_ENCODING` ile reddeder; dead-letter'a özgün baytlar **Base64** olarak konur (kanıt bozulmaz).

Eski `file` kaynağı kalır (v4 yapılandırmaları çalışmaya devam eder); fark dokümante edilmiştir.

### 5. Doğrulama: biçim ≠ anlam

`parse` "satır CLF mi?" sorusunu yanıtlar. `validate` (`ValidationStage`) "değerler makul mü?" sorusunu yanıtlar ve
kuralları **bildirimli** olarak yapılandırmadan alır: `required`, `validIp`, `minStatus/maxStatus`, `minBytes/maxBytes`,
`notBefore/notAfter`. 1970 ve 2099 tarihli satırlar sözdizimce geçerlidir; "makul aralık" bir **iş kuralıdır** ve
kodda sabitlenmez, hat başına belirlenir. IP denetimi DNS çözümlemesi yapmaz (ağ erişimi, yavaşlık ve yan etki yok):
yalnızca sözdizimi.

### 6. Mutabakat: "kayıp yok" bir iddia değil, denetimdir

`RunSummary`, okunan her kaydın akıbetini **tam bir sayaca** yazar:

```
okunan = yazılan + filtrelenen + atlanan + dead-letter + durduran
```

- `okunan`: `TrackingSource` (kaynakta). `yazılan`: `GuardedSink` (başarılı yazmada). Diğerleri: yönlendirici ve filtre.
- Denklem tutmuyorsa kayıt **sessizce kaybolmuştur**: durum `MISMATCH`, çıkış kodu **3**
  (`ManagedRunTest.silentlyDroppedRecordIsDetectedAsMismatchWithExitThree`).
- **Filtre bildirimi.** Hiçbir şey yaymayan bir aşama ya filtredir ya da hatalıdır; ayırt etmek için fabrika
  `filtering()` bildirir. Filtre olmayan bir aşama sessizce kayıt yutarsa mutabakat bunu yakalar. Bu yüzden sayaçlar
  kendi kendini doğrulayan bir sözleşmedir, süs değil.
- Durdurma da hesapta: `fail-fast` ile kesilen çalışmada durduran kayıt `durduran`a yazılır; okunmamış satırlar
  `okunan`a girmez, denklem yine tutar.

### 7. Çıkış kodları ve özet

| Kod | Durum | Anlam |
|---|---|---|
| 0 | `OK` | Bitti, mutabakat tamam |
| 1 | `ABORTED` / `FAILED` | `fail-fast` durdurdu ya da G/Ç hatası (özet nedeni yazar) |
| 2 | — | Hatalı argüman ya da yapılandırma (çalışma **başlamadan**; v4'ten beri) |
| 3 | `MISMATCH` | Kayıt kaybı tespit edildi |

Özet `stderr`'e yazılır (stdout veriye ayrılmıştır, borulanabilir). `ManagedRun` dışarıya istisna sızdırmaz; dead-letter
hedefini açar ve **her durumda** kapatır.

### 8. Yapılandırma

```yaml
stages:
  - type: decode
    on-error: dead-letter
  - type: validate
    params: { validIp: true, notBefore: "2000-01-01T00:00:00Z" }
    on-error: skip
sink:
  type: file
  params: { path: out/valid.txt }
  on-error: retry(3)
  fallback: dead-letter
errors:
  dead-letter: { path: out/rejected.jsonl }
```

Yükleme sırasında (tek satır işlenmeden) denetlenenler: bilinmeyen politika; aşamada `retry`; `fallback` retry'sız;
`fallback: retry`; `dead-letter` politikası var ama `errors.dead-letter` yok; kaynakta `on-error`.

### 9. Dead-letter kaydı

`out/rejected.jsonl`, satır başına bir JSON nesnesi: `timestamp, stage, errorType, message, line, rawInput,
rawTruncated` ve (okuma hatalarında) `rawBase64`. `rawInput` en çok 1024 karakter, `rawBase64` en çok 4096 bayt:
dead-letter dosyası, 100 KiB'lık bir satır yüzünden şişmez. Dosya, hata olmasa bile oluşturulur (boş dosya = "hata yok"
kanıtı).

### 10. Sınırlamalar

- Aşamalar kayıt başına 1→0/1 varsayar. 1→n yayan bir aşama için mutabakat denklemi genişletilmelidir.
- Yeniden oynatma (`replay`: dead-letter'ı yeniden hatta vermek) yok; JSONL biçimi bunu mümkün kılar.
- Hat tek iş parçacıklıdır: "şu anki satır" bağlamı yönlendiricide tutulur. Paralel aşama bunu kaydın içine taşımayı gerektirir.
- `console` sink'i `ProjectedRecord` bekler; `project` aşaması olmadan kullanılamaz (v4'ten beri).

## Artım 4 — Yapılandırma Odaklı Birleştirme ve Aşama Kaydı

**Ana fikir:** hattın *topolojisi* (hangi kaynak, hangi aşamalar, hangi sırada, hangi sink) artık Java
kodunda değil, bir **YAML dosyasındadır**. Yeni bir hat kurmak için kod değişmez ve **yeniden derleme gerekmez**:
aynı `logflow.jar`, farklı yapılandırma dosyasıyla farklı davranır.

```
logflow --config configs/error-report.yaml
     │
     ▼
MiniYaml.parse ──▶ PipelineConfig.from ──▶ PipelineLoader.build ──▶ PipelineBuilder ──▶ Pipeline.run()
 (metin → Map/List)  (yapı doğrulama)       │  ▲                    (v3'ten, değişmedi)
                                            ▼  │
                      SourceRegistry / StageRegistry / SinkRegistry
                      (tür adı → fabrika; META-INF/services ile keşfedilir)
                                            │
            FileSourceFactory  ParseStageFactory  FilterStageFactory  ProjectStageFactory  FileSinkFactory  ConsoleSinkFactory
```

### 1. Yapılandırma şeması

```yaml
pipeline:                       # zorunlu, eşleme
  name: error-report            # isteğe bağlı, metin; yoksa dosya adı (uzantısız) kullanılır

  source:                       # zorunlu
    type: file                  #   zorunlu, metin: kayıtlı bir kaynak türü
    params: { path: data/access-small.log }   # isteğe bağlı eşleme; parametreleri türe göre değişir

  stages:                       # zorunlu, liste, EN AZ BİR öğe, sıra önemli
    - type: parse               #   her öğe: type (zorunlu) + params (isteğe bağlı)
      params: { format: clf }
    - type: filter
      params: { field: status, op: ">=", value: 400 }
    - type: project
      params: { fields: [timestamp, clientIp, status, path] }

  sink:                         # zorunlu
    type: file
    params: { path: out/errors.txt }
```

**Yapı kuralları** (`PipelineConfig`): kök anahtar `pipeline` olmalı; `pipeline` altında yalnızca
`name, source, stages, sink`; her bileşende yalnızca `type, params`. **Bilinmeyen anahtar hata verir**,
sessizce yok sayılmaz (`stage:` yazım hatasını yakalar).

**Tür bazında parametreler** (her biri ilgili fabrikanın `create` metodunda doğrulanır):

| Bileşen | `type` | Parametreler | Giriş → çıkış tipi |
|---|---|---|---|
| kaynak | `file` | `path` (metin, zorunlu): dosya var ve okunabilir olmalı | — → `String` (satır) |
| aşama | `parse` | `format` (metin, isteğe bağlı, yalnızca `clf`) | `String` → `LogRecord` |
| aşama | `filter` | `field`, `op`, `value` (üçü de zorunlu, aşağıdaki tablo) | `LogRecord` → `LogRecord` |
| aşama | `project` | `fields` (metin listesi, zorunlu, boş olamaz) | `LogRecord` → `ProjectedRecord` |
| sink | `file` | `path` (metin, zorunlu); üst dizinler gerekirse oluşturulur | `ProjectedRecord` → — |
| sink | `console` | yok | `ProjectedRecord` → — |

`filter` için alanın türü, geçerli işleçleri ve `value` tipini belirler:

| `field` | alan türü | geçerli `op` | `value` tipi |
|---|---|---|---|
| `status`, `bytes` | sayısal | `==  !=  >  >=  <  <=` | tam sayı (`400`) |
| `clientIp`, `method`, `path`, `userAgent` | metin | `==  !=  startsWith  endsWith  contains` | metin (`/api`); sayı verilirse hata |
| `timestamp` | zaman | `==  >  >=  <  <=` | ISO metni (`"2023-10-10T11:00:00Z"`) |

`project` için geçerli alanlar: `timestamp, clientIp, method, path, status, bytes, userAgent, query`.
`>=` gibi işleçler YAML'da **tırnak içinde** yazılmalıdır.

### 2. Kayıt sistemi (registry): veri odaklı, switch yok

- `StageFactory` / `SourceFactory` / `SinkFactory`: bir türün **adını** (`type()`), **giriş/çıkış tiplerini**
  ve **nasıl kurulacağını** (`create(Params)`) bildirir.
- `StageRegistry` / `SourceRegistry` / `SinkRegistry` (ortak taban `Registry<F>`): tür adı → fabrika eşlemesi.
  Fabrikalar **`java.util.ServiceLoader`** ile `META-INF/services/` dosyalarından keşfedilir:

  ```
  src/main/resources/META-INF/services/edu.logflow.config.StageFactory
      edu.logflow.plugins.ParseStageFactory
      edu.logflow.plugins.FilterStageFactory
      edu.logflow.plugins.ProjectStageFactory
  ```
- Kodda `switch (type) { case "filter": ... }` **yoktur**. Tür bilgisi veridedir (servis dosyası).
  Aynı ad iki kez kaydedilirse sessizce ezilmez, hata verir. Bilinmeyen türde kullanılabilir türler listelenir ve
  yazım hatası için öneri sunulur (`'fliter'` → `'filter'`).

**Yeni bir aşama türü eklemek** (mevcut hiçbir dosyayı değiştirmeden):

1. `StageFactory` uygulayan bir sınıf yaz (ad, giriş/çıkış tipi, `create`). Örnek: `RegistryTest.UpperCaseStageFactory`.
2. Sınıf adını `META-INF/services/edu.logflow.config.StageFactory` dosyasına bir satır olarak ekle
   (testlerde ya da kodda `registry.register(new ...)` de yeterli).
3. Yapılandırmada `type: <ad>` olarak kullan. `RegistryTest.newStageTypeCanBeRegisteredWithoutTouchingExistingOnes`
   bunu gösterir.

Yeni **hat** için ise (yeni tür değil) hiçbir şey derlenmez: yalnızca yeni bir `.yaml` dosyası yazılır.

### 3. Yükleyici (`PipelineLoader`)

Sırasıyla: (1) YAML'ı ayrıştır, (2) yapıyı doğrula, (3) her bileşenin türünü kayıt defterinde ara,
(4) `Params` ile parametreleri doğrulayıp bileşeni kur, (5) bir önceki adımın çıktı tipi ile bir sonrakinin
giriş tipinin uyuştuğunu denetle, (6) hepsi geçerliyse `PipelineBuilder` ile birleştir.

- **Tip denetimi yüklemede yapılır.** v3'te `PipelineBuilder` tip uyumunu *derleyiciye* yaptırıyordu. Topoloji
  artık dosyada olduğundan derleyici göremez; yükleyici aynı kuralı çalışma başlamadan önce uygular
  (her fabrika giriş/çıkış `Class` bilgisini bildirir). Bu yüzden birleştirme adımındaki tek bir ham-tip
  (`raw type`) kullanımı güvenlidir ve yorumla belgelenmiştir.
- Uyuşmazlık iletisi çözümü de söyler: *"Araya şu aşamalardan biri gerekir: project"* (kayıt defterinde
  girişi ve çıkışı uyan aşamalar aranır).
- `Params`: tip denetimli erişim (`requireString/Int/StringList`), `allowOnly` ile bilinmeyen parametre reddi.
  Her hata bileşeni (`stages[1] ('filter')`), sorunu ve örnek `params` değerini içerir.

### 4. Yapılandırma zamanı hataları ≠ çalışma zamanı veri hataları

| | Yapılandırma zamanı hatası | Çalışma zamanı veri hatası |
|---|---|---|
| Örnek | bilinmeyen aşama türü, eksik/yanlış tipte parametre, boş hat, tip uyuşmazlığı, kaynak dosya yok | bozuk günlük satırı, geçersiz durum kodu |
| Ne zaman | **yüklemede**, tek satır okunmadan, tek dosya yazılmadan | veri işlenirken, satır satır |
| Neden oluşur | **ayarı yazan kişinin** hatası; veriyle ilgisi yok | veri kirli; kullanıcı hatası değil |
| Doğru tepki | **hemen ve yüksek sesle başarısız ol** (fail fast, fail loudly): iletiyle çık | çalışmaya devam et, hatayı say/ayır |
| Bu projede | `ConfigException` → ileti + **çıkış kodu 2**, hiçbir çıktı dosyası oluşmaz | `ParserStage` satırı atlar ve sayar (nedenleri: **5. hafta**) |

`--dry-run`, yapılandırmayı yalnızca doğrular (`Yapilandirma gecerli: error-report: file -> parse -> filter -> project -> file`)
ve hiçbir veri işlemez; testler (`invalidConfigurationTouchesNoFiles`, `dryRunValidatesWithoutProcessingAnyData`)
çıktı dosyasının ve dizinin **oluşmadığını** doğrular.

Çıkış kodları: `0` başarılı; `1` çalışma hatası (ör. çıktı yazılamadı); `2` hatalı argüman **ya da**
yapılandırma hatası.

### 5. Mimari tanım ile mimari gerçekleştirimin ayrılması

- **Tanım (ne):** YAML dosyası. Hangi adımlar, hangi sırayla, hangi parametrelerle. Okunur, sürüm kontrolüne
  girer, yeniden derleme olmadan değişir.
- **Gerçekleştirim (nasıl):** Java sınıfları ve fabrikalar. Her aşama yalnızca kendi işini bilir
  ve YAML'dan habersizdir (`ProjectionStage` hâlâ düz bir Java sınıfıdır; konfigürasyonu okuyan tek yer
  fabrikadır).
- Bağ: **tür adı**. İkisi arasındaki sözleşme `type` dizgisi ve fabrikanın bildirdiği tiplerdir.
  `PipelineBuilder` (v3) hiç değişmedi; yükleyici onun bir istemcisidir. Bu, 3. haftanın "komut satırı
  seçenekleri → `App.buildPipeline`" yolunun genellenmesidir: orada topoloji 3 sabit adımdı, burada dosyadan okunur.

### 6. Üç yapılandırma, tek çalıştırılabilir dosya

Üçü de `data/access-small.log` (14 geçerli + 4 bozuk satır) üzerinde çalışır, aynı `logflow` ile:

| Dosya | Hat | Çıktı |
|---|---|---|
| `configs/error-report.yaml` | `parse → filter(status>=400) → project(timestamp, clientIp, status, path)` | `out/errors.txt` (3 satır) |
| `configs/api-traffic.yaml` | `parse → filter(path startsWith /api) → project(timestamp, method, path, status)` | `out/api-traffic.txt` (3 satır) |
| `configs/bandwidth-by-client.yaml` | `parse → filter(bytes > 0) → project(clientIp, bytes)` | `out/bandwidth-by-client.txt` (11 satır) |

Gerçek çıktılar (`logflow --config ...`):

```
out/errors.txt
timestamp=2023-10-10T10:56:01Z	clientIp=10.0.0.5	status=401	path=/api/login
timestamp=2023-10-10T10:57:30Z	clientIp=172.16.4.2	status=404	path=/missing/page
timestamp=2023-10-10T11:00:00Z	clientIp=203.0.113.7	status=500	path=/api/items/42

out/api-traffic.txt
timestamp=2023-10-10T10:56:01Z	method=POST	path=/api/login	status=401
timestamp=2023-10-10T10:56:07Z	method=POST	path=/api/login	status=200
timestamp=2023-10-10T11:00:00Z	method=DELETE	path=/api/items/42	status=500

out/bandwidth-by-client.txt  (ilk 3 satır)
clientIp=192.168.1.10	bytes=2326
clientIp=192.168.1.11	bytes=5120
clientIp=10.0.0.5	bytes=128
```

`ConfigIntegrationTest.sameExecutableCodeWithThreeConfigsGivesThreeDifferentOutputs` bunu otomatik doğrular.
`configs/invalid/` altındaki 7 hatalı örnek, README'deki hata iletilerinin kaynağıdır ve testle
(`shippedInvalidExamplesAllFailFastWithExitCode2`) hep çıkış kodu 2 verdiği doğrulanır.

### 7. YAML alt kümesi (`MiniYaml`)

Bu ortamda dış kütüphane (SnakeYAML) indirilemediği ve proje bağımlılıksız kaldığı için küçük bir
ayrıştırıcı yazıldı. Desteklenen: blok eşleme/liste, akış `{ a: 1 }` / `[a, b]`, tırnaklı ve düz skalerler
(sayı, true/false, null), yorumlar, `---`. **Desteklenmeyenler sessizce yanlış okunmaz; satır numarasıyla
hata verir:** TAB girinti, `|` / `>` çok satırlı metin, yinelenen anahtar, kapanmayan tırnak/parantez,
yanlış girinti. Çapa/etiket (`&`, `*`, `!!`) desteklenmez. `MiniYaml.parse` tek giriş noktasıdır; ileride gerçek
bir YAML kütüphanesine geçmek yalnızca o çağrıyı değiştirir.

*Doğrulama:* ayrıştırıcının gerçek bir YAML kütüphanesiyle aynı sonucu verdiği, bir kereye mahsus yapılan
karşılaştırmayla denendi: SnakeYAML 2.0 ve `MiniYaml`, `configs/` altındaki 10 dosyanın ve ödev metnindeki
tek boşluk girintili örneğin (ile blok biçimli bir varyantın) hepsinde **aynı yapıyı** üretti (fark: 0).
Bu karşılaştırma yapıya dahil değildir (bağımlılık eklememek için); kalıcı güvence `MiniYamlTest` ve
`PipelineLoaderTest` testleridir.

### 8. Sınırlamalar

- Topoloji **doğrusaldır** (kaynak → aşamalar → tek sink); dallanma/birleşme yok.
- "İstemci bazında bant genişliği" şimdilik **veri çıkarımıdır** (her yanıtın istemcisi ve bayt sayısı);
  istemci başına *toplam* için hattın sonunda birikimi boşaltan durumlu bir aşama gerekir ve `Stage`
  arayüzünde "girdi bitti" kancası yoktur. Sonraki bir artımın işi.
- Birden çok `filter` AND ile bağlanır (art arda aşamalar); OR için ayrı bir aşama türü gerekir.
- Göreli dosya yolları **çalışma dizinine** göre çözülür.
- Hatalı veri satırları hâlâ yalnızca sayılır (5. hafta).

---

## Artım 3 — Bileşim: Filtreleme, Projeksiyon ve Pipeline Builder

Bu belge v3'teki mimari kararları ve **aşama sırası deneyini** anlatır. Deney sayıları gerçek
ölçümlerdir (aşağıda yöntem ve tekrar çalıştırma komutu var).

### 1. Genel görünüm

```
FileLineSource ─String─▶ ParserStage ─LogRecord─▶ PredicateFilterStage* ─LogRecord─▶ ProjectionStage ─ProjectedRecord─▶ FileSink
   (Source)                (Stage)                    (Stage, 0..n kez)                    (Stage)                      (Sink)
```

Hat `PipelineBuilder` ile kurulur (ayrıntı: bölüm 4). Aşamalar birbirini tanımaz; yalnızca
girdi/çıktı **tiplerini** bilir. Bu yüzden bir aşama eklemek ya da çıkarmak komşuları değiştirmez.

### 2. Üç yeni aşama ve tek sorumluluk

| Aşama | Tek sorumluluğu | Kod satırı* |
|---|---|---|
| `PredicateFilterStage` | Kaydı yalnızca `Predicate<LogRecord>` doğruysa iletir. Dönüştürmez, yazmaz. | 23 |
| `ProjectionStage` | Kaydı seçilen alanların alt kümesine indirger. Elemez, yazmaz. | 39 |
| `FileSink` | Kayıtları dosyaya yazar; dosyanın yaşam döngüsünü yönetir. | 35 |

\* Yorum, boş satır ve `import` hariç. Hazır koşullar: `minStatus(n)`, `pathPrefix(p)`,
`timeBetween(since, until)` (`since <= zaman < until`, sınırlardan biri `null` olabilir).

Eleme (filtre) ile dönüştürme (projeksiyon) **aynı mimari öğedir**: ikisi de `Stage`'dir, `Emitter`'a
0, 1 ya da çok öğe yayar. Filtre "bazen hiç yaymayan" aşama, projeksiyon "her zaman bir kez yayan"
aşamadır. İki sınıfın `process` gövdesi birkaç satırdır ve fark yalnızca ne yaydıklarıdır.

### 3. Sink yaşam döngüsü ve FileSink

`Sink` arayüzüne `open()` ve `close()` kancaları eklendi (varsayılan gövdeleri boştur, bu yüzden
`ConsoleSink` ve lambda sink'ler etkilenmedi). `PipelineBuilder.to(...)` ile kurulan hat:

1. `open()` — ilk öğeden önce,
2. `accept(...)` — her öğe için,
3. `close()` — **her durumda** sonda (kaynak ya da bir aşama istisna fırlatsa bile).

`close()` sırasında ikinci bir hata olursa asıl hata maskelenmez; `addSuppressed` ile eklenir.

**Neden süs değil?** `FileSink` bir `BufferedWriter` tutar. `close()` (dolayısıyla flush) çağrılmazsa
yazılan satırlar arabellekte kalır ve dosyaya ulaşmaz. Hata da verilmez. Bu davranış
`FileSinkTest.withoutCloseBufferedDataNeverReachesTheFile` testiyle gösterilir: `accept` çağrıldıktan sonra
dosya hâlâ 0 bayttır, `close()` sonrası satır oradadır. `PipelineBuilderTest` ise `close()`'un
başarılı, hatalı-aşama ve hatalı-kaynak durumlarında çağrıldığını doğrular.

### 4. PipelineBuilder: akıcı ve tip güvenli birleştirme

```java
Pipeline p = PipelineBuilder.from(new FileLineSource(in))
        .via(new ParserStage())
        .via(PredicateFilterStage.minStatus(400))
        .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
        .to(new FileSink(out));
```

`PipelineBuilder<S>` içindeki `S`, hattın **şu anki** öğe tipidir. `via(Stage<S,R>)` bir
`PipelineBuilder<R>` döndürür; `to(Sink<S>)` hattı bitirir. Sonuçlar:

- Aynı tipli aşama (ör. `LogRecord -> LogRecord` filtre) **herhangi bir sırada ve sayıda** eklenebilir
  (`PipelineBuilderTest.sameStageTypeCanBeAddedAnyNumberOfTimes`).
- Tipler uyuşmayan bağlantı **derlenmez**. Hata çalışma zamanına kaçmaz.
- 4. haftada yapılandırma dosyası bu builder'ı çağıracak. `App.buildPipeline` bugün aynı işi
  komut satırı seçenekleriyle yapıyor: seçenek verildiyse `builder = builder.via(...)`.

### 5. Veri minimizasyonu

`ProjectionStage` seçilmeyen alanı kayıttan **çıkarır**: bu aşamadan sonra hiçbir aşama o veriyi
göremez ve çıktıya girmez (`ProjectionStageTest.dataMinimisationDroppedFieldsAreGone`). İki kazanç:

- **Çıktı boyutu:** user agent gibi uzun alanlar atılınca dosya küçülür.
- **Gizlilik:** istemci IP'si gibi kişisel veri, analiz için gerekmiyorsa hiç yazılmaz. "Gerekmeyen veriyi
  toplama/saklama" ilkesinin (data minimisation) en basit uygulaması budur.

Ham satır (`raw`) varsayılan alan kümesine bilerek alınmadı: her şeyi içerdiği için minimizasyonu
bozar. `--fields` ile de seçilemez. Bilinmeyen alan adı hata verir (sessizce yok sayılmaz).

### 6. Komut satırı

```
logflow <girdi> [--output dosya] [--min-status N] [--path-prefix önek]
                [--since an] [--until an] [--fields a,b,c]
```

Örnek: `./logflow data/access-small.log --min-status 400 --path-prefix /api --fields timestamp,clientIp,status --output out.txt`

Seçenek verilmeyen filtre hatta **hiç eklenmez**. `--output` yoksa sonuç ekrana yazılır; özet
(atlanan hatalı satır sayısı) `stderr`'e gider, böylece `stdout` başka bir programa borulanabilir.
Çıkış kodu: 0 başarılı, 1 çalışma hatası (ör. dosya yok), 2 hatalı argüman.

### 7. Deney: aşama sırası

Soru: Aşamaları farklı sırada bağlamak **sonucu** değiştirir mi? **Maliyeti** değiştirir mi?

**Yöntem.** `OrderExperiment` (`src/main/java/edu/logflow/OrderExperiment.java`): sabit tohumlu
(42) 1.000.000 kayıt; `status >= 400` yaklaşık %5 (50.038 kayıt), `path` `/api` ile başlayan
yaklaşık %30 (299.187 kayıt), ikisi birbirinden bağımsız. Kayıtlar bellekte hazır `LogRecord`'dur,
yani ayrıştırma ve disk maliyeti ölçüme karışmaz. 3 ısınma + 21 ölçüm; iki varyant **sırayla
(A, B, A, B, ...)** ölçülür, medyan alınır. Ortam: 2 vCPU'lu bir konteyner, JDK 21. Sayılar
makineden makineye değişir; oranlar ve sayaçlar değişmez. Tekrar:
`mvn -q package -DskipTests` sonra `java -Xmx2g -cp target/classes edu.logflow.OrderExperiment`.

### 7.1 İki filtrenin sırası (ikisi de `LogRecord -> LogRecord`)

| Sıra | Koşul çağrısı | Medyan süre (3 çalıştırma) | Çıktı |
|---|---|---|---|
| A: `status>=400` → `path /api` | 1.050.038 | 36–40 ms | 15.105 kayıt |
| B: `path /api` → `status>=400` | 1.299.187 | 44–47 ms | 15.105 kayıt (aynı, aynı sırada) |

- **Sonuç aynı.** İki filtre de saf (durumsuz) bir koşuldur ve kayıtların sırasını değiştirmez; bu
  yüzden sıra mantıksal sonucu etkilemez (`a.equals(b)` doğrulandı).
- **Maliyet farklı.** İlk filtre N kez, ikincisi yalnızca ilkini geçenler için çalışır:
  A için N + 0,05·N = 1,05 M, B için N + 0,30·N = 1,30 M çağrı. Ölçülen sayaçlar bu hesapla birebir
  uyuşur. Süre farkı yaklaşık %18–20'dir (B daha yavaş). Kural: **en çok eleyen (ve ucuz olan) filtre
  önce**. Koşullar bu kadar ucuz olduğu için fark küçüktür; her çağrı regex ya da ağ araması olsaydı
  fark çağrı sayısıyla orantılı büyürdü.
- **Sınır:** "sıra serbest" yalnızca birbirinden bağımsız saf filtreler için doğrudur. Durumlu
  bir aşama (tekilleştirme, sayma, hız sınırlama) sıraya duyarlıdır.

*Ölçüm dersi:* ilk denemede varyantları art arda (önce tüm A koşuları, sonra tüm B) ölçtüğümde
B daha hızlı çıktı, yani çağrı sayısının tersi. Bu bir ölçüm artefaktıydı (JIT ısınması, aynı çağrı
noktasına gelen farklı lambdalar). Sırayla (interleaved) ölçünce sonuç tutarlı hâle geldi ve üç
çalıştırmada da A hızlıdır. Mikro-ölçümde ölçüm yöntemi sonucu etkileyebilir.

### 7.2 Ayrıştırmadan önce filtrelemek: mümkün değil

`ParserStage`, `String` alır ve `LogRecord` verir; `PredicateFilterStage` ise `LogRecord` bekler. Sırayı
değiştirmeye çalışınca derleyici reddeder (gerçek çıktı):

```
error: method via in class PipelineBuilder<S> cannot be applied to given types;
                .via(PredicateFilterStage.minStatus(400))
  required: Stage<String,R>
  found:    PredicateFilterStage
  reason: cannot infer type-variable(s) R
    (argument mismatch; PredicateFilterStage cannot be converted to Stage<String,R>)
```

Bir satır `status` alanı ayrıştırılmadan bilinmez; "ayrıştırmadan önce filtre" ancak ham metin
üzerinde çalışan başka bir aşama (`String -> String`, ör. boş satırı atan) olabilir. O aşama
farklı bir tip imzasına sahiptir, sıra serbestliği bu tipe göre belirlenir.

### 7.3 Projeksiyondan önce mi sonra mı filtrelemek: gerçek bir ödünleşim

| Sıra (filtre: `status>=400`) | Projeksiyon çağrısı | Medyan süre (3 çalıştırma) | Çıktı |
|---|---|---|---|
| C: filtre → projeksiyon | 50.038 | 39–42 ms | 50.038 kayıt |
| D: projeksiyon → filtre | 1.000.000 | 169–186 ms | 50.038 kayıt (aynı) |

- **Performans:** C, projeksiyonu yalnızca geçen %5 için yapar; D her kayıt için bir `Map`
  kurar ve %95'ini çöpe atar. Ölçülen fark yaklaşık **4,3 kat**.
- **Doğruluk:** D'de filtre `ProjectedRecord` üzerinde çalışır ve `status` alanına projeksiyonun
  bırakmış olmasına **bağımlıdır**. `status`'u atan bir projeksiyondan sonra aynı filtre
  **0 kayıt** döndürdü (beklenen 50.038). Hata fırlatılmaz, sonuç sessizce yanlıştır. Bu, "filtre önce"
  sıralamasının güvenli olmasının nedenidir.
- **Gizlilik tarafı:** D'nin tek avantajı, hassas alanların hattın daha erken noktasında atılmasıdır.
  C'de filtre aşaması tam kaydı (IP dahil) görür. Aşamalar aynı süreçte çalıştığı için bu
  fark küçüktür, ama aşamalar ayrı servislerse ağırlık kazanır.

**Karar:** `App.buildPipeline` önce filtreleri, **sonra** projeksiyonu bağlar. Aynı sıra tip
sistemiyle de desteklenir: `ProjectedRecord` üzerinde `PredicateFilterStage` zaten kullanılamaz.

### 8. Sınırlamalar ve sonraki adım

- Hatalı satırlar hâlâ yalnızca sayılır (nedenleri ve ayrı kanal 5. haftada).
- Filtre koşulları AND ile bağlanır; OR için yeni bir koşul ya da aşama gerekir.
- 4. haftada `App.buildPipeline`, komut satırı yerine yapılandırma dosyasından beslenecek.
