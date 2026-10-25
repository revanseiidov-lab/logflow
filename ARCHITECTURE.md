# LogFlow Mimarisi

Bu belge iki artımı kapsar: **Artım 4** (yapılandırma odaklı birleştirme ve aşama kaydı, en güncel) ve
**Artım 3** (bileşim, filtreleme, projeksiyon; aşama sırası deneyi). Artım 3 bölümü aşağıda aynen durur.

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
