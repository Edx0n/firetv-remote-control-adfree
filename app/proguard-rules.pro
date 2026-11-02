# OkHttp e coroutines já trazem suas próprias regras de consumidor.
# O app usa org.json (parte do Android) e nenhuma reflexão própria.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
