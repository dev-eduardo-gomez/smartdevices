# smartdevices

Aplicación Android con **autenticación clásica por email y contraseña** construida con Jetpack Compose y Firebase Authentication. Incluye inicio de sesión, creación de cuenta, recuperación de contraseña y un *seam* para obtener el JWT (ID token) de Firebase.

> 📘 ¿Querés entender cómo funciona todo por dentro? Leé la guía completa y explicativa en **[`docs/AUTENTICACION.md`](docs/AUTENTICACION.md)**.

---

## ✨ Características

- **Inicio de sesión** con email y contraseña.
- **Crear cuenta** con envío de email de verificación *fire-and-forget* (no bloquea el acceso).
- **Olvidé mi contraseña** con mensaje genérico *enumeration-safe* (no revela si el email existe).
- **JWT seam**: expone `getIdToken()` para usar el ID token de Firebase como `Authorization: Bearer <token>` contra un backend propio.
- **Validación previa** de email y contraseña antes de tocar la red.
- **Mensajes de error** traducidos de los códigos de Firebase a texto humano.

---

## 🛠️ Stack

| Tecnología | Detalle |
|-----------|---------|
| Lenguaje | Kotlin 2.2.10 |
| Build | Android Gradle Plugin 9.2.1 (Kotlin integrado) |
| UI | Jetpack Compose (BOM 2026.02.01) + Material 3 |
| Arquitectura | MVVM + capas (domain / data / presentation) |
| Navegación | Navigation Compose 2.8.5 |
| Backend de identidad | Firebase Authentication (BOM 33.7.0) |
| Async | Kotlin Coroutines + StateFlow |
| SDK | `compileSdk 36` · `minSdk 24` · Java 11 |

---

## 🚀 Cómo correr el proyecto

### Requisitos previos

- Android Studio (versión reciente, compatible con AGP 9.2.1).
- Un proyecto de **Firebase** con **Email/Password** habilitado.

### Pasos

1. Cloná el repositorio y abrilo en Android Studio.
2. En la [consola de Firebase](https://console.firebase.google.com), registrá una app Android con el package name:
   ```
   com.utcam.smartdevices
   ```
3. Descargá el `google-services.json` y colocálo en la carpeta del módulo app:
   ```
   app/google-services.json
   ```
   > ⚠️ Sin este archivo la app **compila pero crashea al iniciar** (Firebase no inicializa).
4. En Firebase Console → **Authentication → Sign-in method**, habilitá **Email/Password**.
5. Sincronizá Gradle y ejecutá la app.

---

## 🧪 Tests

El proyecto sigue **TDD** en la lógica unit-testeable (validación, mapeo de errores y la máquina de estados del ViewModel).

```bash
./gradlew testDebugUnitTest -x processDebugGoogleServices
```

> El flag `-x processDebugGoogleServices` es necesario para correr los tests sin el `google-services.json` (los unit tests no necesitan Firebase real).

**Cobertura:** `AuthValidator`, `mapFirebaseAuthError` y `AuthViewModel` (con un `FakeAuthRepository` + Turbine). La UI y la navegación se validan de forma manual (fuera del alcance de los unit tests).

---

## 🗂️ Estructura

```
app/src/main/java/com/utcam/smartdevices/
├── MainActivity.kt              # Hostea el AuthNavHost
└── auth/
    ├── domain/                  # Kotlin puro, sin Android ni Firebase
    │   ├── IAuthRepository.kt    # Contrato de operaciones de auth
    │   ├── AuthValidator.kt      # Validación de email y contraseña
    │   └── AuthError.kt          # Códigos de Firebase → mensajes legibles
    ├── data/
    │   └── AuthRepository.kt     # Implementación que habla con Firebase
    └── presentation/
        ├── AuthUiState.kt        # Estados: Idle / Loading / Success / Error
        ├── AuthViewModel.kt      # Orquesta: valida → repo → emite estado
        ├── AuthViewModelFactory.kt
        ├── AuthRoutes.kt         # Constantes de rutas
        ├── AuthNavHost.kt        # Grafo de navegación
        └── screens/
            ├── LoginScreen.kt
            ├── RegisterScreen.kt
            ├── ForgotPasswordScreen.kt
            └── HomeScreen.kt
```

**Flujo de datos:** las pantallas observan un `StateFlow<AuthUiState>` del `AuthViewModel`, que valida los inputs, llama a `IAuthRepository` y emite el nuevo estado. La UI reacciona con `collectAsState` + `LaunchedEffect`.

---

## 🔑 Sobre el JWT

El **ID token** que emite Firebase **ya es un JWT** (RS256, firmado por Google, ~1 h de validez). No se genera ningún JWT propio en el cliente. La app expone `getIdToken()` en el repositorio como *seam*: el token queda disponible en `AuthUiState.Success(idToken)`, listo para enviarse como `Authorization: Bearer <token>` a un backend propio el día que exista. Hoy es solo el seam, sin integración de backend.

---

## 📚 Documentación

- **[`docs/AUTENTICACION.md`](docs/AUTENTICACION.md)** — guía técnica completa y explicativa: arquitectura por capas, flujos paso a paso, validación, manejo de errores, testing y glosario.
