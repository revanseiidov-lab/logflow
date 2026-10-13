# LogFlow Mimarisi — Artım 3: Bileşim, Filtreleme, Projeksiyon

Bu belge v3'teki mimari kararları ve **aşama sırası deneyini** anlatır. Deney sayıları gerçek
ölçümlerdir (aşağıda yöntem ve tekrar çalıştırma komutu var).

## 1. Genel görünüm

```
FileLineSource ─String─▶ ParserStage ─LogRecord─▶ PredicateFilterStage* ─LogRecord─▶ ProjectionStage ─ProjectedRecord─▶ FileSink
   (Source)                (Stage)                    (Stage, 0..n kez)                    (Stage)                      (Sink)
```

Hat `PipelineBuilder` ile kurulur (ayrıntı: bölüm 4). Aşamalar birbirini tanımaz; yalnızca
girdi/çıktı **tiplerini** bilir. Bu yüzden bir aşama eklemek ya da çıkarmak komşuları değiştirmez.

## 2. Üç yeni aşama ve tek sorumluluk

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

## 3. Sink yaşam döngüsü ve FileSink

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

## 4. PipelineBuilder: akıcı ve tip güvenli birleştirme

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

## 5. Veri minimizasyonu

`ProjectionStage` seçilmeyen alanı kayıttan **çıkarır**: bu aşamadan sonra hiçbir aşama o veriyi
göremez ve çıktıya girmez (`ProjectionStageTest.dataMinimisationDroppedFieldsAreGone`). İki kazanç:

- **Çıktı boyutu:** user agent gibi uzun alanlar atılınca dosya küçülür.
- **Gizlilik:** istemci IP'si gibi kişisel veri, analiz için gerekmiyorsa hiç yazılmaz. "Gerekmeyen veriyi
  toplama/saklama" ilkesinin (data minimisation) en basit uygulaması budur.

Ham satır (`raw`) varsayılan alan kümesine bilerek alınmadı: her şeyi içerdiği için minimizasyonu
bozar. `--fields` ile de seçilemez. Bilinmeyen alan adı hata verir (sessizce yok sayılmaz).

## 6. Komut satırı

```
logflow <girdi> [--output dosya] [--min-status N] [--path-prefix önek]
                [--since an] [--until an] [--fields a,b,c]
```

Örnek: `./logflow data/access-small.log --min-status 400 --path-prefix /api --fields timestamp,clientIp,status --output out.txt`

Seçenek verilmeyen filtre hatta **hiç eklenmez**. `--output` yoksa sonuç ekrana yazılır; özet
(atlanan hatalı satır sayısı) `stderr`'e gider, böylece `stdout` başka bir programa borulanabilir.
Çıkış kodu: 0 başarılı, 1 çalışma hatası (ör. dosya yok), 2 hatalı argüman.

## 7. Deney: aşama sırası

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

## 8. Sınırlamalar ve sonraki adım

- Hatalı satırlar hâlâ yalnızca sayılır (nedenleri ve ayrı kanal 5. haftada).
- Filtre koşulları AND ile bağlanır; OR için yeni bir koşul ya da aşama gerekir.
- 4. haftada `App.buildPipeline`, komut satırı yerine yapılandırma dosyasından beslenecek.
