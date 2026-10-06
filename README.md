# LogFlow — Yazılım Mimarisi Dönem Projesi

Günlük (log) dosyalarını **işlem hattı (pipeline)** mimarisiyle işleyen küçük bir Java uygulaması.
Bu sürüm: **v2 — Tipli Kayıtlar ve İlk Gerçek Filtre** (Artım 2).

## Çalıştırma

Gereksinimler: JDK 17+ ve Maven 3.9+.

```bash
mvn -q package          # testleri çalıştırır, jar üretir, kapsama raporu oluşturur
./logflow data/access-small.log        # Windows: logflow.bat data\access-small.log
```

Çıktı: her geçerli satır için bir satır, en sonda hatalı (atlanan) satır sayısı.

```
2023-10-10T10:55:36Z  192.168.1.10     GET    /index.html  200  2326B  "Mozilla/5.0 (Windows NT 10.0; ...)"
...
Hatali (atlanan) satir sayisi: 4
```

## Mimari

```
LineSource ──String──▶ ParserStage ──LogRecord──▶ ConsoleSink
 (Source)               (Stage)                    (Sink)
```

| Parça | Sorumluluk |
|---|---|
| `Source<T>` / `LineSource` | Öğeleri üretir. Dosya sistemine dokunan **tek** yer. |
| `Stage<I,O>` | Bir girdiyi işler, çıktıları `Emitter<O>`'a yayar (0, 1 ya da çok çıktı). |
| `Emitter<T>` | Bir aşamanın çıktı kanalı. |
| `Sink<T>` / `ConsoleSink` | Son durak: öğeyi tüketir (burada: yazdırır). |
| `Pipeline` | Tip güvenli kurucu: `Pipeline.from(src).then(stage).to(sink).run()` |
| `LogRecord` | Alan modeli (domain model): değişmez (immutable) `record`. |
| `ParserStage` | CLF satırı → `LogRecord`. Hatalı satırı atlar ve sayar. |

### Bu hafta ne değişti? (v1 → v2)

Hat artık **dizgi (string) değil, alan modeli** taşıyor. `ParserStage`, `Pipeline.from(...)` ile
`.to(...)` arasına tek bir `.then(parser)` satırıyla eklendi. `Pipeline`, `Stage`, `Emitter`, `Sink`,
`Source` ve `LineSource` **değişmedi**. Değişen mevcut dosyalar yalnızca `Main` (aşamayı bağlar) ve
`ConsoleSink` (artık `LogRecord` yazar). Ayrıntı: [CHANGELOG.md](CHANGELOG.md).

### Tasarım kararları

- **Sorumlulukların ayrılması:** okuma (`LineSource`), ayrıştırma (`ParserStage`), gösterme (`ConsoleSink`)
  ayrı sınıflar; birbirlerini değil yalnızca tipleri bilirler.
- **`LogRecord` değişmezdir.** `attributes` eşlemesi kurucuda `Map.copyOf` ile kopyalanır; dışarıdan
  değiştirilemez, `null` verilirse boş eşleme olur.
- **`attributes` neden var?** Kayıt biçimi genişletmeye açık olmalıdır. 5.–7. haftalarda sonraki aşamalar
  kayda bugünkü ayrıştırıcının hiçbir şey bilmediği verileri ekleyecektir (zenginleştirme, etiketleme...).
  Her yeni veri için record'a alan eklemek onu kullanan tüm aşamaları ve testleri etkilerdi. Sabit ortak
  alanlar tipli kalır, açık uçlu veri `attributes` içinde taşınır. Şimdilik ayrıştırıcı yalnızca sorgu
  dizesini (`query`) buraya koyar; `path` sorgu dizesi içermez.
- **Biçim:** CLF + isteğe bağlı birleşik biçim (`"referer" "user-agent"`). User agent yoksa boş dizgi.
  Bayt alanındaki `-` değeri `0` sayılır. Zaman damgası `Instant` (UTC) olarak saklanır.

### Bilinen sınırlama: hatalı satırlar (bu hafta)

Hatalı satırlar **yalnızca atlanır ve sayılır**: hata nedeni tutulmaz, ayrı bir kanala gönderilmez,
istisna fırlatılmaz. Boş satır da hatalı sayılır. Bunların doğru biçimde ele alınması **5. haftada**
gelecek — mimari katman katman inşa ediliyor.

## Testler

JUnit 5. `mvn test` ile çalışır.

- `ParserStageTest` — 11 test (istenen 8 durumun hepsi + `-` bayt, saf CLF, karışık satırlar).
- `LogRecordTest` — değişmezlik, kopyalama, `null` eşleme.
- `ConsoleSinkTest` — tek satırlık biçim.
- `PipelineTest` — aşama sırası, araya aşama ekleme, `LineSource`.

**Aşama testleri dosya sistemine dokunmaz.** `CollectingEmitter` (test dublörü) bir aşamanın yaydığı
her şeyi bir listede toplar: test aşamayı oluşturur, `process("...satır...", emitter)` çağırır ve
`emitter.items()` ile sonucu doğrular. Sonraki haftalarda her aşama testinde yeniden kullanılacak.

### Satır kapsaması (line coverage)

Rapor: `mvn test` sonrası `target/site/jacoco/index.html`.

> **Satır kapsaması: `__ %`** — *(buraya `mvn test` sonrası JaCoCo raporundaki "Lines" yüzdesini yazın)*

## Demo soruları

**Ayrıştırıcıyı eklemek için mevcut dosyalardan hangilerini değiştirdiniz?**
Yalnızca iki tane: `Main.java` (hatta `.then(parser)` eklendi ve sonda sayaç yazdırılıyor) ve
`ConsoleSink.java` (görev gereği artık `LogRecord` gösteriyor). `Pipeline`, `Stage`, `Emitter`, `Sink`,
`Source`, `LineSource` aynı kaldı. Doğrulama: `git diff --stat v1 v2`.

**Ayrıştırıcı testiniz bir dosya okumuyor — bu nasıl mümkün oluyor ve neden önemli?**
Aşama dosyayı bilmez: `process(String, Emitter)` sadece bir dizgi alır ve sonucu bir `Emitter`'a yayar.
Dosya okuma `LineSource`'un işidir. Test, aşamaya doğrudan bir dizgi verir ve `CollectingEmitter` ile
çıktıyı yakalar. Önemi: testler hızlıdır, dosyalara/ortama bağlı değildir (yarıştan ve temizlik
sorunlarından arınmıştır), her hatalı satır türü tek satırla üretilebilir ve bir test başarısız
olduğunda suçlu yalnızca ayrıştırıcıdır. Bu, sorumlulukların ayrılmasının doğrudan sonucudur.

## Dizin yapısı

```
data/access-small.log        örnek günlük
src/main/java/edu/logflow/   Emitter, Stage, Sink, Source, LineSource, Pipeline,
                             LogRecord, ParserStage, ConsoleSink, Main
src/test/java/edu/logflow/   CollectingEmitter, ParserStageTest, LogRecordTest,
                             ConsoleSinkTest, PipelineTest
logflow, logflow.bat         başlatıcı betikler
```
