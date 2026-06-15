# Autenticación por email y contraseña — Documentación técnica

Esta documentación explica, desde cero, la feature de autenticación clásica construida para la aplicación `smartdevices`. Cubre las pantallas de inicio de sesión, creación de cuenta y recuperación de contraseña, incluyendo la arquitectura que las sostiene, los flujos completos, la validación, el manejo de errores y los tests unitarios. Si nunca trabajaste con Android, Jetpack Compose o Firebase, esta guía es para vos.

**Tecnologías utilizadas:** Jetpack Compose (UI declarativa), Firebase Authentication (backend de identidad), Kotlin (lenguaje), patrón MVVM (Model-View-ViewModel) como arquitectura de presentación.

---

## 1. Mapa rápido — cómo están organizadas las capas

```
┌─────────────────────────────────────────────────────────────┐
│                     UI  (Compose Screens)                   │
│   LoginScreen  RegisterScreen  ForgotPasswordScreen  Home   │
│        │              │                │               │    │
│        └──────────────┴────────────────┴───────────────┘    │
│                              │                              │
│                    observa StateFlow<AuthUiState>           │
│                              │                              │
├──────────────────────────────┼──────────────────────────────┤
│                     AuthViewModel                           │
│   Valida inputs → llama al repositorio → emite estado      │
│                              │                              │
│                    depende de IAuthRepository               │
├──────────────────────────────┼──────────────────────────────┤
│                     IAuthRepository  (contrato/interfaz)    │
│                              │                              │
│              implementado por AuthRepository                │
├──────────────────────────────┼──────────────────────────────┤
│                     Firebase Authentication                 │
│          createUserWithEmailAndPassword / signIn            │
│          sendPasswordResetEmail / getIdToken                │
└─────────────────────────────────────────────────────────────┘
```

### ¿Por qué separar en capas?

| Capa | Responsabilidad | Por qué existe separada |
|------|----------------|-------------------------|
| **UI** (`screens/`) | Renderizar y capturar input | Solo pinta estado; no sabe nada de Firebase |
| **ViewModel** | Orquestar: validar → llamar repo → emitir estado | Sobrevive rotaciones de pantalla; testeable sin UI |
| **IAuthRepository** (interfaz) | Definir el contrato de operaciones | Permite reemplazar Firebase por un `FakeAuthRepository` en tests |
| **AuthRepository** (impl.) | Hablar con Firebase | Único punto de contacto con código de terceros |
| **Firebase Auth** | Almacenar credenciales, emitir tokens | Infraestructura externa; el resto del código no depende de ella directamente |

> Regla clave: si mañana reemplazamos Firebase por otro proveedor, solo cambia `AuthRepository`. El resto del código no se toca.

---

## 2. Arquitectura por capas — archivo por archivo

### 2.1 Capa domain — las reglas del negocio

La capa `domain` no tiene imports de Android ni de Firebase. Es Kotlin puro. Eso es intencional: se puede testear con cualquier herramienta JVM estándar, sin emulador.

---

#### `IAuthRepository.kt`

**Qué es:** Una `interface` de Kotlin — un contrato que define qué operaciones de autenticación existen, sin decir cómo se implementan.

**Por qué existe:** La UI y el ViewModel no deben conocer a Firebase directamente. Dependen de esta interfaz. En producción se usa `AuthRepository` (que sí habla con Firebase). En tests se usa `FakeAuthRepository` (que devuelve resultados controlados). Ambas implementan `IAuthRepository`.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/domain/IAuthRepository.kt
interface IAuthRepository {
    val currentUser: FirebaseUser?

    suspend fun signUp(email: String, password: String): Result<FirebaseUser>
    suspend fun signIn(email: String, password: String): Result<FirebaseUser>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun getIdToken(forceRefresh: Boolean = false): Result<String>

    fun signOut()
}
```

Todos los métodos de red son `suspend` (funcionan con coroutines). Devuelven `Result<T>`: si todo salió bien, contiene el valor; si falló, contiene la excepción. Esto evita los try/catch en el ViewModel.

---

#### `AuthValidator.kt`

**Qué es:** Un `object` (singleton) con dos funciones de validación puras: una para el email y otra para la contraseña.

**Por qué existe separado:** La validación se ejecuta antes de llamar a Firebase. Si los datos son inválidos, no tiene sentido gastar una petición de red. Además, al ser código puro sin Android, los tests son simples funciones JVM.

**Por qué NO usa `android.util.Patterns.EMAIL_ADDRESS`:** Esa clase pertenece al SDK de Android, que no existe en un test JVM normal (solo en Robolectric o en un emulador). Usando una `Regex` propia, los tests son rápidos y no necesitan ningún framework especial.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/domain/AuthValidator.kt
object AuthValidator {
    private val EMAIL_REGEX = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    fun isValidEmail(email: String): Boolean = email.matches(EMAIL_REGEX)
    fun isValidPassword(password: String): Boolean = password.length >= 8
}
```

La regex verifica el patrón mínimo: algo@algo.algo. Firebase es la validación final y autoritativa; esto es solo un filtro previo para mejorar la experiencia de usuario.

La contraseña pide al menos 8 caracteres (Firebase solo exige 6, pero se eligió 8 para guiar mejor al usuario).

---

#### `AuthError.kt`

**Qué es:** Una función de nivel superior (`fun mapFirebaseAuthError(exception: Throwable): String`) que convierte una excepción de Firebase en un mensaje legible para el usuario.

**Por qué existe:** Firebase devuelve errores con códigos internos como `"ERROR_WRONG_PASSWORD"`. La UI no debería conocer esos códigos. Esta función actúa como traductor.

**Por qué es una función y no una clase:** No tiene estado ni necesita instanciación. Una función pura es lo más simple posible.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/domain/AuthError.kt
fun mapFirebaseAuthError(exception: Throwable): String {
    val errorCode = (exception as? FirebaseAuthException)?.errorCode
        ?: return FALLBACK_MESSAGE

    return when (errorCode) {
        "ERROR_INVALID_EMAIL"       -> "Please enter a valid email address."
        "ERROR_EMAIL_ALREADY_IN_USE"-> "An account with this email already exists."
        "ERROR_WRONG_PASSWORD"      -> "Incorrect password. Please try again."
        "ERROR_INVALID_CREDENTIAL"  -> "Incorrect email or password. Please try again."
        // ... más casos ...
        else                        -> FALLBACK_MESSAGE
    }
}
```

Si la excepción no es un `FirebaseAuthException` (por ejemplo, una excepción de red genérica), devuelve el mensaje de fallback genérico. Tabla completa de mapeos en la sección 6.

---

### 2.2 Capa data — la implementación real

#### `AuthRepository.kt`

**Qué es:** La única clase que habla con Firebase. Implementa `IAuthRepository`.

**Por qué existe separada:** Concentra todo el código de Firebase en un solo lugar. Si el día de mañana Firebase cambia su API, solo hay que modificar este archivo.

**Patrón `runCatching` + `.await()`:**

Firebase usa el tipo `Task<T>` (del SDK de Google Play Services), que es una forma de manejar operaciones asíncronas que no es Kotlin nativo. La extensión `.await()` del paquete `kotlinx-coroutines-play-services` convierte ese `Task` en una coroutine suspendible. `runCatching { }` envuelve la llamada y captura cualquier excepción, devolviendo un `Result<T>` sin propagar excepciones.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/data/AuthRepository.kt
override suspend fun signUp(email: String, password: String): Result<FirebaseUser> = runCatching {
    val result = auth.createUserWithEmailAndPassword(email, password).await()
    val user = requireNotNull(result.user) { "Sign-up succeeded but returned no user" }
    // Fire-and-forget email verification; failure must never gate success (AUTH-4)
    runCatching { user.sendEmailVerification().await() }
    user
}
```

**¿Qué significa "fire-and-forget"?** El `sendEmailVerification()` está envuelto en su propio `runCatching` anidado. Esto significa: "intentá mandar el email de verificación, pero si falla, ignoralo". El resultado de esa operación se descarta. La razón: si Firebase falla al enviar el email de verificación, eso no debería impedirle al usuario acceder a la app. La cuenta ya fue creada exitosamente.

**`getIdToken`:**

```kotlin
override suspend fun getIdToken(forceRefresh: Boolean): Result<String> = runCatching {
    val user = requireNotNull(auth.currentUser) { "No authenticated user" }
    user.getIdToken(forceRefresh).await().token ?: error("Firebase returned null token")
}
```

Obtiene el Firebase ID token del usuario autenticado. Con `forceRefresh = false`, Firebase devuelve el token cacheado si aún es válido (dura ~1 hora). Este token es un JWT firmado por Google. Más detalles en la sección 5.

---

### 2.3 Capa presentation — lo que el usuario ve y toca

#### `AuthUiState.kt`

**Qué es:** Una `sealed interface` que modela todos los estados posibles de la UI de autenticación. Piensala como una máquina de estados.

**Por qué `sealed`:** Kotlin garantiza que el `when` que evalúa el estado sea exhaustivo: el compilador avisa si te olvidás de cubrir un caso.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/presentation/AuthUiState.kt
sealed interface AuthUiState {
    data object Idle    : AuthUiState   // estado inicial, sin actividad
    data object Loading : AuthUiState   // operación en curso
    data class  Success(val idToken: String?) : AuthUiState  // operación exitosa
    data class  Error(val message: String)    : AuthUiState  // algo salió mal
}
```

La máquina de estados es siempre lineal: `Idle → Loading → Success | Error`. Después de que la UI procesa un estado terminal (`Success` o `Error`), debe llamar a `consumeState()` para volver a `Idle`.

---

#### `AuthViewModel.kt`

**Qué es:** El ViewModel compartido por las tres pantallas de autenticación (Login, Register, ForgotPassword). Es el cerebro de la operación: valida, llama al repositorio y emite el estado.

**Qué es un ViewModel:** Es una clase que sobrevive las rotaciones de pantalla. Cuando girás el teléfono, la pantalla se destruye y se recrea, pero el ViewModel sigue vivo. Así no se pierde el estado de la operación.

**Por qué se inyecta el `ioDispatcher`:**

```kotlin
class AuthViewModel(
    private val repository: IAuthRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel()
```

En producción, `ioDispatcher` es `Dispatchers.IO` (un pool de hilos para operaciones de red). En tests, se inyecta un `StandardTestDispatcher` que usa tiempo virtual, permitiendo controlar exactamente cuándo avanzan las coroutines.

**Cómo funciona `signIn`:**

```kotlin
fun signIn(email: String, password: String) {
    if (!validateEmailAndPassword(email, password)) return   // 1. validar
    _uiState.value = AuthUiState.Loading                     // 2. Loading sincrónico
    viewModelScope.launch {
        val result = withContext(ioDispatcher) {             // 3. llamada en hilo IO
            repository.signIn(email, password)
        }
        result
            .onSuccess { _uiState.value = AuthUiState.Success(fetchToken()) }
            .onFailure { _uiState.value = AuthUiState.Error(mapFirebaseAuthError(it)) }
    }
}
```

El estado `Loading` se emite **antes** de lanzar la coroutine. Esto garantiza que la UI siempre vea el spinner antes de que la operación de red comience.

**`StateFlow`:** Es un flujo de datos observable. La UI se suscribe a él y se re-renderiza automáticamente cada vez que el estado cambia. Es similar a LiveData pero más moderno y compatible con Compose.

**`consumeState()`:** Resetea el estado a `Idle`. Las pantallas deben llamarlo después de procesar un `Success` o `Error` para evitar que el estado obsoleto se re-ejecute si Compose re-compone la pantalla.

---

#### `AuthViewModelFactory.kt`

**Qué es:** Una `ViewModelProvider.Factory` — la clase que Android necesita para crear el ViewModel con parámetros personalizados (el repositorio).

**Por qué existe (inyección de dependencias manual):** Android no puede crear un ViewModel con constructor parametrizado por sí solo. La `Factory` le dice cómo hacerlo. Esto es "DI manual": en lugar de un framework como Hilt o Dagger, nosotros mismos construimos el objeto con sus dependencias.

```kotlin
// app/src/main/java/com/utcam/smartdevices/auth/presentation/AuthViewModelFactory.kt
class AuthViewModelFactory(
    private val repository: IAuthRepository = AuthRepository(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(repository) as T
    }
}
```

En `AuthNavHost`, se usa así:
```kotlin
val viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory())
```

En tests, el ViewModel se crea directamente sin Factory:
```kotlin
viewModel = AuthViewModel(repository = fakeRepo, ioDispatcher = testDispatcher)
```

---

#### `AuthRoutes.kt`

**Qué es:** Un `object` con constantes `String` que representan las rutas de navegación.

**Por qué existe:** Evita escribir strings literales como `"login"` dispersos por el código. Si hay un typo, el compilador no puede detectarlo; con constantes, el IDE y el compilador sí ayudan.

```kotlin
object AuthRoutes {
    const val LOGIN    = "login"
    const val REGISTER = "register"
    const val FORGOT   = "forgot"
    const val HOME     = "home"
}
```

---

#### `AuthNavHost.kt`

**Qué es:** El composable que define el grafo de navegación de autenticación. Conecta las 4 pantallas con sus rutas.

**Cómo determina la pantalla inicial:**

```kotlin
val startDestination = remember {
    if (viewModel.isLoggedIn) AuthRoutes.HOME else AuthRoutes.LOGIN
}
```

Si Firebase ya tiene una sesión activa (el usuario no cerró sesión antes), la app abre directamente en Home.

**Política de back-stack:** Cuando el login o registro es exitoso, el destino es Home y todas las pantallas de auth se eliminan del historial de navegación (`popUpTo(LOGIN, inclusive = true)`). Si el usuario presiona "Atrás" desde Home, la app se cierra (no vuelve al Login). Esto es el comportamiento esperado: no querés que alguien "accidentalmente" vuelva a la pantalla de login después de autenticarse.

**`lastIdToken`:** Es un estado que captura el JWT antes de navegar (porque justo después se llama `consumeState()` que resetea el estado a `Idle`). `HomeScreen` recibe este token para mostrarlo al usuario.

---

#### Las 4 pantallas (`screens/`)

Todas son funciones `@Composable` — las unidades básicas de Jetpack Compose. Un `@Composable` es una función que describe una porción de la UI. Cuando el estado cambia, Compose "recompone" solo las partes afectadas.

**`LoginScreen.kt`**

Muestra campos de email y contraseña, un botón de inicio de sesión, y links a Register y ForgotPassword.

```kotlin
// Patrón de reacción al estado en todas las pantallas
val uiState by viewModel.uiState.collectAsState()

LaunchedEffect(uiState) {
    if (uiState is AuthUiState.Success) {
        onAuthenticated((uiState as AuthUiState.Success).idToken)
        viewModel.consumeState()
    }
}
```

`collectAsState()` convierte el `StateFlow` en un estado de Compose. `LaunchedEffect(uiState)` se ejecuta cada vez que `uiState` cambia: si el nuevo estado es `Success`, navega y consume el estado. Mientras hay `Loading`, el botón se deshabilita y aparece un `CircularProgressIndicator`.

**`RegisterScreen.kt`**

Igual que `LoginScreen` pero llama a `viewModel.signUp()`. Cuando vuelve atrás (a Login), llama a `viewModel.consumeState()` primero para limpiar cualquier error que haya quedado.

**`ForgotPasswordScreen.kt`**

Llama a `viewModel.resetPassword()`. Usa `DisposableEffect` en lugar de `LaunchedEffect` para limpiar el estado al salir de la pantalla:

```kotlin
DisposableEffect(Unit) {
    onDispose { viewModel.consumeState() }
}
```

Cuando la pantalla desaparece de la composición (el usuario navega hacia atrás), `consumeState()` se llama automáticamente para que el estado de esta pantalla no "contamine" a Login.

**`HomeScreen.kt`**

Pantalla post-autenticación. Muestra "You are signed in." y el estado del JWT. No muestra el token en sí (evita leakage), solo si fue adquirido o no. El botón Sign Out llama a `viewModel.signOut()` (sincrónico) y luego al callback `onSignedOut`.

**`MainActivity.kt`**

Punto de entrada de la app. Solo hace una cosa: renderizar `AuthNavHost` dentro del tema:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
        SmartdevicesTheme {
            AuthNavHost()
        }
    }
}
```

`setContent` reemplaza al tradicional `setContentView(R.layout.*)`. Es el puente entre Android Activity y el mundo Compose.

---

## 3. Cómo funciona la autenticación, paso a paso

### 3.1 Flujo A — Iniciar sesión (Login)

```
Usuario escribe email + password y toca "Sign In"
          │
          ▼
LoginScreen.onClick → viewModel.signIn(email, password)
          │
          ▼
    AuthValidator.isValidEmail()  ──── FALLA ──→  uiState = Error("Please enter a valid email address.")
    AuthValidator.isValidPassword() ── FALLA ──→  uiState = Error("Password must be at least 8 characters.")
          │ OK
          ▼
    uiState = Loading  ← (sincrónico, antes de lanzar la coroutine)
          │
          ▼  viewModelScope.launch { withContext(Dispatchers.IO) { ... } }
    repository.signIn(email, password)
          │
          ├── ÉXITO ──→ fetchToken() → repository.getIdToken()
          │                │
          │                ├── token OK ──→ uiState = Success("eyJ...")
          │                └── token FALLA ─→ uiState = Success(null)  ← auth OK, token es best-effort
          │
          └── FALLA ──→ mapFirebaseAuthError(exception) ──→ uiState = Error("Incorrect password...")
          │
          ▼
LoginScreen observa uiState via collectAsState() + LaunchedEffect
          │
          ├── Loading  ──→ muestra CircularProgressIndicator, deshabilita botón
          ├── Error    ──→ muestra mensaje en rojo inline
          └── Success  ──→ onAuthenticated(idToken) → navController.navigate(HOME)
                                                      popUpTo(LOGIN, inclusive=true)
```

### 3.2 Flujo B — Crear cuenta (Register)

El flujo es idéntico al login hasta el paso de Firebase, con una diferencia:

```
repository.signUp(email, password)
    │
    ▼  (dentro de AuthRepository)
auth.createUserWithEmailAndPassword(email, password).await()
    │
    ├── usuario creado ──→ runCatching { user.sendEmailVerification().await() }
    │                          │
    │                          ├── email enviado ──→ se ignora el resultado (fire-and-forget)
    │                          └── email falló   ──→ se ignora el error  (fire-and-forget)
    │                      devuelve el usuario igualmente
    │
    └── error ──→ Result.failure(exception)
```

**¿Qué significa fire-and-forget?** Es como enviar un mensaje de texto y no esperar respuesta. La app le dice a Firebase "mandá un email de verificación" y sigue adelante sin importar si eso funcionó. El motivo es que si el email de verificación falla (problema de red, cuota, etc.), no queremos bloquear al usuario recién registrado. Su cuenta ya existe y puede usar la app.

### 3.3 Flujo C — Olvidar contraseña (ForgotPassword)

```
Usuario escribe email y toca "Send Reset Link"
          │
          ▼
viewModel.resetPassword(email)
          │
    AuthValidator.isValidEmail() ── FALLA ──→ uiState = Error (sin llamar a Firebase)
          │ OK
          ▼
    uiState = Loading
          │
          ▼
    repository.sendPasswordReset(email)
          │
          ├── ÉXITO ──→ uiState = Success(idToken = null)
          │                │
          │                └── ForgotPasswordScreen detecta Success:
          │                    muestra "If an account exists for this email, a reset link has been sent."
          │                    NO navega automáticamente
          │                    el botón se deshabilita (para no mandar el link dos veces)
          │
          └── FALLA ──→ uiState = Error(mensaje)
```

**¿Por qué el mensaje es genérico y no dice si el email existe o no?**

Esto es una práctica de seguridad llamada "evitar enumeración de usuarios". Si la app dijera "ese email no está registrado", un atacante podría probar miles de emails y descubrir cuáles tienen cuenta. Con el mensaje genérico, el atacante no obtiene esa información.

**¿Por qué no navega automáticamente al Login después del éxito?** El usuario puede querer ver el mensaje de confirmación, o puede equivocarse de email y querer intentar de nuevo. La navegación es siempre una decisión del usuario (toca "Back to Login" explícitamente).

---

## 4. Cómo fluye el estado desde el ViewModel hasta la pantalla

```
AuthViewModel._uiState (MutableStateFlow)
        │
        │  .asStateFlow()  ← solo lectura desde afuera
        ▼
AuthViewModel.uiState (StateFlow<AuthUiState>)
        │
        │  .collectAsState()  ← dentro del @Composable
        ▼
val uiState by viewModel.uiState.collectAsState()
        │
        │  Compose recompone cuando cambia
        ▼
LaunchedEffect(uiState) {
    if (uiState is AuthUiState.Success) {
        onAuthenticated(...)
        viewModel.consumeState()   ← vuelve a Idle para no re-ejecutar en recomposición
    }
}
```

`LaunchedEffect` es una forma de ejecutar código de efecto secundario (como navegar) en respuesta a un cambio de estado. El parámetro `uiState` es la "clave": cada vez que `uiState` cambia, el efecto se cancela y se relanza. `consumeState()` es crucial: sin él, si Compose recompone la pantalla mientras el estado sigue siendo `Success`, el efecto se volvería a ejecutar y navegaría dos veces.

---

## 5. El tema del JWT, explicado bien

### ¿Qué es un JWT?

Un JWT (JSON Web Token) es un string codificado en Base64 con tres partes separadas por puntos: `header.payload.signature`. El header dice el algoritmo de firma, el payload contiene claims (datos sobre el usuario y la sesión), y la signature permite verificar que el token no fue alterado.

### ¿Firebase ya emite JWTs?

Sí. El Firebase ID token que devuelve `getIdToken()` **ya es un JWT**. Lo firma Google usando el algoritmo RS256 (RSA + SHA-256). Tiene una validez de aproximadamente 1 hora. El payload contiene el UID del usuario, el email, el nombre del proyecto de Firebase, y timestamps de emisión y expiración.

No se genera ningún JWT propio en el cliente. No hay ninguna criptografía custom. Google lo hace por nosotros.

### ¿Qué es el "seam" de JWT?

Un "seam" (costura, en arquitectura de software) es un punto diseñado para enchufar funcionalidad futura sin cambiar el resto del código. `getIdToken()` en `IAuthRepository` es ese punto.

```kotlin
// El seam: ya existe, ya se llama, ya se surfacea en AuthUiState.Success
override suspend fun getIdToken(forceRefresh: Boolean): Result<String> = runCatching {
    val user = requireNotNull(auth.currentUser) { "No authenticated user" }
    user.getIdToken(forceRefresh).await().token ?: error("Firebase returned null token")
}
```

Hoy el token solo se muestra en `HomeScreen` como "Session token acquired." — no se manda a ningún backend.

**¿Para qué serviría en el futuro?** Si la app tuviera un backend propio (una API REST), el cliente debería mandarlo en cada request como header HTTP:

```
Authorization: Bearer <idToken>
```

El backend verificaría ese token contra los servidores de Google para confirmar que el usuario está autenticado. El seam está listo para eso: solo habría que leer `Success.idToken` en el momento de hacer la request.

**¿Por qué si `getIdToken` falla igual se emite `Success`?**

La autenticación ya sucedió. El usuario ya tiene sesión con Firebase. El token es un dato adicional de conveniencia. Si la obtención falla (problema de red momentáneo), no tendría sentido decirle al usuario "error de autenticación" cuando en realidad está autenticado. Por eso se emite `Success(idToken = null)`.

---

## 6. Validación y manejo de errores

### Reglas de validación (AuthValidator)

| Campo | Regla | Implementación |
|-------|-------|----------------|
| Email | Debe coincidir con `^[^@\s]+@[^@\s]+\.[^@\s]+$` | `email.matches(EMAIL_REGEX)` |
| Password | Mínimo 8 caracteres | `password.length >= 8` |

La validación es sincrónica y ocurre **antes** de lanzar ninguna coroutine. Si falla, el ViewModel emite `Error` directamente sin tocar Firebase.

### Tabla de errores de Firebase → mensaje de usuario

| Código Firebase | Mensaje al usuario |
|----------------|-------------------|
| `ERROR_INVALID_EMAIL` | `"Please enter a valid email address."` |
| `ERROR_EMAIL_ALREADY_IN_USE` | `"An account with this email already exists."` |
| `ERROR_WEAK_PASSWORD` | `"Password must be at least 8 characters."` |
| `ERROR_USER_NOT_FOUND` | `"No account found for this email."` |
| `ERROR_WRONG_PASSWORD` | `"Incorrect password. Please try again."` |
| `ERROR_INVALID_CREDENTIAL` | `"Incorrect email or password. Please try again."` |
| `ERROR_USER_DISABLED` | `"This account has been disabled."` |
| `ERROR_NETWORK_REQUEST_FAILED` | `"Check your internet connection and try again."` |
| `ERROR_TOO_MANY_REQUESTS` | `"Too many attempts. Please try again later."` |
| Cualquier otro / excepción genérica | `"Something went wrong. Please try again."` |

`ERROR_INVALID_CREDENTIAL` merece mención especial: Firebase a veces devuelve este código en lugar de `ERROR_WRONG_PASSWORD` para no revelar si el problema es el email o la contraseña. El mensaje es intencionalmente ambiguo.

---

## 7. Cómo se probó (testing)

### ¿Qué es TDD en una frase?

Test-Driven Development (Desarrollo Guiado por Tests) significa escribir el test antes que el código de producción. Define el comportamiento esperado primero; luego implementás hasta que el test pase.

### ¿Qué está cubierto por unit tests?

Los tests viven en `app/src/test/` y se ejecutan en la JVM, sin emulador ni dispositivo:

**`AuthValidatorTest`** (11 casos)
- Email válido, subdominio válido, sin @, vacío, sin dominio, con espacios, sin punto en dominio
- Contraseña de exactamente 8 chars, más de 8, 7 chars, vacía

**`AuthErrorTest`** (12 casos)
- Cada código de error de Firebase mapeado al mensaje correcto
- Código desconocido → fallback
- `IOException` genérica → fallback
- `RuntimeException` genérica → fallback

**`AuthViewModelTest`** (18+ casos) — usando `FakeAuthRepository` y Turbine

Cubre:
- Estado inicial es `Idle`
- Secuencia `Idle → Loading → Success` con token
- Secuencia `Idle → Loading → Error` en fallo de repo
- Validación bloquea la llamada al repo (repo no se llama con inputs inválidos)
- `signUp` happy path y error path
- `resetPassword` emite `Success(null)` en éxito
- `resetPassword` emite `Error` en fallo de red
- JWT seam: fallo de `getIdToken` no convierte el `Success` en `Error`
- `getIdToken` se llama exactamente una vez después de `signIn`/`signUp` exitoso
- `consumeState()` vuelve a `Idle`
- `isLoggedIn` refleja correctamente si hay usuario activo
- `signOut` llama al repo y resetea estado

### Herramientas de test

| Herramienta | Para qué se usa |
|-------------|----------------|
| `FakeAuthRepository` | Implementación de `IAuthRepository` para tests; resultados programables, contadores de llamadas |
| `MainDispatcherRule` / `Dispatchers.setMain(testDispatcher)` | Reemplaza `Dispatchers.Main` por un dispatcher de test para controlar el tiempo virtual |
| `StandardTestDispatcher` + `advanceUntilIdle()` | Hace que las coroutines no corran de forma ansiosa; `advanceUntilIdle()` las drena todas |
| Turbine (`app.cash.turbine`) | Hace testeable un `Flow`; `awaitItem()` espera el próximo valor emitido |
| Mockito | Para crear un mock de `FirebaseUser` (clase `final` que no se puede extender normalmente) |

### ¿Qué NO está cubierto por unit tests y por qué?

| Qué | Por qué no |
|-----|-----------|
| Llamadas reales a Firebase | Requeriría un proyecto de Firebase real; son tests de integración o E2E |
| Renderizado de la UI (Compose) | Requiere `AndroidJUnitRunner` y el composable test harness (tests de instrumentación) |
| Navegación real entre pantallas | Requiere `NavController` real dentro de un entorno Android |

### Cómo correr los tests unitarios

```bash
./gradlew testDebugUnitTest -x processDebugGoogleServices
```

**¿Por qué `-x processDebugGoogleServices`?** El plugin de Google Services (`com.google.gms.google-services`) necesita el archivo `app/google-services.json` para generar código de inicialización de Firebase. Sin ese archivo, la tarea `processDebugGoogleServices` falla. Los unit tests no usan Firebase real (usan `FakeAuthRepository`), así que se puede saltear esa tarea de forma segura con `-x`.

---

## 8. Dependencias que se agregaron y por qué

Las versiones están declaradas en `gradle/libs.versions.toml` (version catalog) y los artefactos se referencian desde `app/build.gradle.kts`.

| Dependencia | Artefacto | Versión | Para qué sirve |
|-------------|-----------|---------|---------------|
| Firebase BOM | `com.google.firebase:firebase-bom` | `33.7.0` | Gestiona versiones de todas las libs de Firebase de forma coherente |
| Firebase Auth | `com.google.firebase:firebase-auth` | (del BOM) | SDK de autenticación de Firebase |
| Coroutines Play Services | `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | `1.9.0` | La extensión `.await()` que convierte `Task<T>` de Firebase en coroutines |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.8.5` | `NavHost`, `NavController`, `composable()` |
| Lifecycle ViewModel Compose | `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.8.7` | La función `viewModel()` usable desde un `@Composable` |
| Coroutines Test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.9.0` | `runTest`, `StandardTestDispatcher`, `advanceUntilIdle` |
| Turbine | `app.cash.turbine:turbine` | `1.1.0` | Testear `Flow` y `StateFlow` con `awaitItem()` |
| Mockito Core | `org.mockito:mockito-core` | `5.14.2` | Crear mocks de `FirebaseUser` (clase `final`) |

**¿Por qué `navigation-compose` y `lifecycle-viewmodel-compose` tienen versión explícita en lugar de venir del Compose BOM?**

El Compose BOM gestiona las versiones del conjunto de librerías de UI de Compose (`androidx.compose.*`). Navigation y Lifecycle son librerías del grupo `androidx.*` pero están fuera del BOM de Compose. Sus ciclos de lanzamiento son independientes, así que se versionan explícitamente.

---

## 9. Cómo correr la app

Para ejecutar la app en un emulador o dispositivo real, se necesitan dos pasos previos:

1. **Archivo `google-services.json`:** Descargarlo desde la consola de Firebase (Settings del proyecto → Tu app Android) y colocarlo en `app/google-services.json`. Sin este archivo, la app crashea al iniciar con un `FirebaseApp initialization failed`.

2. **Habilitar Email/Password en Firebase Console:** Ir a Authentication → Sign-in method → Email/Password → Habilitar. Sin esto, todos los intentos de login o registro fallan con `ERROR_OPERATION_NOT_ALLOWED`.

Estos dos pasos son de configuración de infraestructura y no están en el código; son requisitos del entorno.

---

## 10. Glosario rápido

| Término | Qué significa |
|---------|--------------|
| **Repository** | Clase que abstrae el origen de datos. La UI no sabe si los datos vienen de Firebase, una base de datos local o una API REST. |
| **Interface / contrato** | En Kotlin, una `interface` define qué métodos existen sin implementarlos. Cualquier clase que la implemente debe proveer esos métodos. Es el "contrato" que el resto del código puede usar sin conocer la implementación. |
| **ViewModel** | Clase del patrón MVVM que mantiene el estado de la UI y sobrevive a las rotaciones de pantalla. |
| **StateFlow** | Un flujo de datos observable que siempre tiene un valor actual. Cuando el valor cambia, todos los colectores son notificados. |
| **Coroutine** | Función asíncrona de Kotlin que puede pausarse y reanudarse sin bloquear el hilo principal. Alternativa ligera a los threads. |
| **suspend** | Palabra clave de Kotlin que marca una función como "puede suspenderse". Solo se puede llamar desde otra función `suspend` o desde una coroutine. |
| **Compose** | Framework de UI declarativo de Android. En lugar de manipular vistas con código imperativo, describís cómo debe verse la UI y Compose se encarga de actualizarla. |
| **Composable** | Función anotada con `@Composable` que describe una porción de la UI. Puede llamar a otras funciones `@Composable`. |
| **Recomposición** | Cuando el estado que una función `@Composable` observa cambia, Compose la vuelve a ejecutar para actualizar la UI. Solo se recomponen las partes afectadas. |
| **Navigation** | Librería de Jetpack para gestionar la navegación entre pantallas. Define destinos (rutas) y las transiciones entre ellos. |
| **DI (Inyección de Dependencias)** | Patrón donde las dependencias de una clase (como el repositorio) se proveen desde afuera en lugar de ser creadas internamente. Facilita el testing y el reemplazo de implementaciones. |
| **JWT (JSON Web Token)** | Token firmado criptográficamente que codifica información de identidad. Formato: `header.payload.signature` en Base64. |
| **ID token** | El JWT específico que Firebase emite para un usuario autenticado. Firmado por Google con RS256, válido ~1 hora. |
| **`runCatching` / `Result`** | `runCatching { }` ejecuta un bloque y captura cualquier excepción, devolviendo un `Result<T>`. `Result.success(value)` o `Result.failure(exception)`. Evita propagar excepciones por el stack. |
| **Dispatcher** | En coroutines, define en qué hilo o pool de hilos corre una coroutine. `Dispatchers.Main` = hilo principal (UI). `Dispatchers.IO` = pool para operaciones de red o disco. `TestDispatcher` = tiempo virtual para tests. |
