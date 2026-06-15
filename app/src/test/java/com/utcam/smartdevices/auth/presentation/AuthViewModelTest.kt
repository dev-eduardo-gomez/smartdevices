package com.utcam.smartdevices.auth.presentation

import app.cash.turbine.test
import com.utcam.smartdevices.auth.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AuthViewModel].
 *
 * Dispatcher strategy — single shared [TestCoroutineScheduler]:
 * - A [TestCoroutineScheduler] owns all virtual time for this test class.
 * - A [StandardTestDispatcher] backed by that scheduler is installed as [Dispatchers.Main]
 *   (replacing [MainDispatcherRule] to reuse the SAME scheduler for both Main and IO).
 * - The same [StandardTestDispatcher] is injected as the ViewModel's [ioDispatcher].
 * - [runTest] is called with [testDispatcher] so it uses the same scheduler.
 *
 * With StandardTestDispatcher, coroutines do NOT run eagerly — they are queued on the
 * scheduler. [advanceUntilIdle] drains all pending tasks. This ensures:
 *   1. signIn()/signUp()/resetPassword() emits Loading synchronously (before launch body runs).
 *   2. advanceUntilIdle() runs the queued withContext body and emits Success/Error.
 *   3. Turbine sees Loading then Success/Error as separate items.
 *
 * FirebaseAuthException note:
 * [com.google.firebase.auth.FirebaseAuthException]'s constructor calls
 * android.text.TextUtils.isEmpty() which is not mocked in JVM tests.
 * Error-path tests use [RuntimeException] — [mapFirebaseAuthError] returns the generic
 * fallback. Exact error-code → message mapping is covered in AuthErrorTest.
 *
 * Covers AUTH-1 through AUTH-7 and AUTH-10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    /**
     * Single scheduler shared by both the Main dispatcher override and the ioDispatcher
     * injection. [runTest(testDispatcher)] also uses this scheduler. All virtual-time
     * advancement is therefore unified: [advanceUntilIdle] drains every queued coroutine.
     */
    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var fakeRepo: FakeAuthRepository
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAuthRepository()
        viewModel = AuthViewModel(
            repository = fakeRepo,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── AUTH-10: initial state ────────────────────────────────────────────────

    @Test
    fun `initial uiState is Idle`() = runTest(testDispatcher) {
        assertEquals(AuthUiState.Idle, viewModel.uiState.value)
    }

    // ── AUTH-1 + AUTH-7 + AUTH-10: sign-in happy path ─────────────────────────

    /**
     * AUTH-1, AUTH-7, AUTH-10: valid credentials → Idle → Loading → Success(idToken).
     *
     * Pattern with StandardTestDispatcher:
     *  1. Call signIn() — sets Loading synchronously, queues launch body on scheduler.
     *  2. awaitItem() → Loading (already in StateFlow).
     *  3. advanceUntilIdle() — runs queued repo call + getIdToken, sets Success.
     *  4. awaitItem() → Success(idToken).
     */
    @Test
    fun `signIn with valid credentials emits Loading then Success with token`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("fake-jwt-token")

            viewModel.uiState.test {
                assertEquals(AuthUiState.Idle, awaitItem())

                viewModel.signIn("user@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())

                advanceUntilIdle()
                assertEquals(AuthUiState.Success("fake-jwt-token"), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-10: Loading is shown as an intermediate state before Firebase responds.
     */
    @Test
    fun `signIn emits Loading as intermediate state before Success`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("tok")

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("user@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── AUTH-2: sign-in error path ─────────────────────────────────────────────

    /**
     * AUTH-2, AUTH-10: repo failure → Loading → Error(mapped message).
     */
    @Test
    fun `signIn with failed repository emits Loading then Error`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.failure(RuntimeException("auth failed"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("user@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                val error = awaitItem()
                assertTrue("Expected Error, got $error", error is AuthUiState.Error)
                assertEquals(
                    "Something went wrong. Please try again.",
                    (error as AuthUiState.Error).message,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── AUTH-3: sign-in validation (pre-Firebase) ─────────────────────────────

    /**
     * AUTH-3: malformed email → Error immediately, repo NOT called.
     * No Loading — validation is synchronous, no coroutine is launched.
     */
    @Test
    fun `signIn with invalid email emits Error without calling repository`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("notanemail", "password123")
                val state = awaitItem()
                assertTrue("Expected Error, got $state", state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, fakeRepo.signInCallCount)
        }

    /**
     * AUTH-3: short password → Error immediately, repo NOT called.
     */
    @Test
    fun `signIn with short password emits Error without calling repository`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("user@example.com", "short")
                val state = awaitItem()
                assertTrue("Expected Error, got $state", state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, fakeRepo.signInCallCount)
        }

    // ── AUTH-4: sign-up happy path ────────────────────────────────────────────

    /**
     * AUTH-4, AUTH-7: successful registration → Loading → Success(idToken).
     */
    @Test
    fun `signUp with valid input emits Loading then Success with token`() =
        runTest(testDispatcher) {
            fakeRepo.signUpResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("signup-jwt-token")

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("newuser@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(AuthUiState.Success("signup-jwt-token"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── AUTH-5: sign-up error mapping ─────────────────────────────────────────

    /**
     * AUTH-5: sign-up fails → Loading → Error.
     */
    @Test
    fun `signUp with repository failure emits Loading then Error`() =
        runTest(testDispatcher) {
            fakeRepo.signUpResult = Result.failure(RuntimeException("email taken"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("taken@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                val error = awaitItem()
                assertTrue(error is AuthUiState.Error)
                assertEquals(
                    "Something went wrong. Please try again.",
                    (error as AuthUiState.Error).message,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-3 applied to sign-up: invalid email → Error, repo NOT called.
     */
    @Test
    fun `signUp with invalid email emits Error without calling repository`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("bad-email", "password123")
                val state = awaitItem()
                assertTrue(state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, fakeRepo.signUpCallCount)
        }

    // ── AUTH-6: reset password ────────────────────────────────────────────────

    /**
     * AUTH-6: valid email → Loading → Success(null).
     * Success(null) = enumeration-safe "email sent" signal (Design D5).
     * ForgotPasswordScreen observes Success and shows the generic confirmation message.
     */
    @Test
    fun `resetPassword with valid email emits Loading then Success(null)`() =
        runTest(testDispatcher) {
            fakeRepo.resetResult = Result.success(Unit)

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.resetPassword("user@example.com")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(AuthUiState.Success(idToken = null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-6: malformed email → Error, repo NOT called.
     */
    @Test
    fun `resetPassword with invalid email emits Error without calling repository`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.resetPassword("bad@")
                val state = awaitItem()
                assertTrue(state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, fakeRepo.resetCallCount)
        }

    /**
     * AUTH-6: network / hard error on reset → Error, NOT Success.
     */
    @Test
    fun `resetPassword with network error emits Error`() =
        runTest(testDispatcher) {
            fakeRepo.resetResult = Result.failure(RuntimeException("network error"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.resetPassword("user@example.com")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                val state = awaitItem()
                assertTrue(state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-6: repo failure (e.g. user not found) → Error.
     */
    @Test
    fun `resetPassword with repository failure emits Error`() =
        runTest(testDispatcher) {
            fakeRepo.resetResult = Result.failure(RuntimeException("user not found"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.resetPassword("unknown@example.com")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                val state = awaitItem()
                assertTrue(state is AuthUiState.Error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── AUTH-7: JWT seam — token failure is NOT an Error ─────────────────────

    /**
     * AUTH-7: signIn succeeds but getIdToken() fails → Success(null), NOT Error.
     */
    @Test
    fun `signIn success with getIdToken failure emits Success(null) not Error`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.failure(RuntimeException("token fetch failed"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("user@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(AuthUiState.Success(idToken = null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-7: signUp succeeds but getIdToken() fails → Success(null), NOT Error.
     */
    @Test
    fun `signUp success with getIdToken failure emits Success(null) not Error`() =
        runTest(testDispatcher) {
            fakeRepo.signUpResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.failure(RuntimeException("token fetch failed"))

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("newuser@example.com", "password123")
                assertEquals(AuthUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(AuthUiState.Success(idToken = null), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * AUTH-7: getIdToken is called after successful signIn (JWT seam exercised).
     */
    @Test
    fun `signIn success triggers getIdToken call`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("jwt")

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signIn("user@example.com", "password123")
                awaitItem() // Loading
                advanceUntilIdle()
                awaitItem() // Success
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeRepo.getIdTokenCallCount)
        }

    /**
     * AUTH-7: getIdToken is called after successful signUp.
     */
    @Test
    fun `signUp success triggers getIdToken call`() =
        runTest(testDispatcher) {
            fakeRepo.signUpResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("jwt")

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("user@example.com", "password123")
                awaitItem() // Loading
                advanceUntilIdle()
                awaitItem() // Success
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeRepo.getIdTokenCallCount)
        }

    // ── consumeState ──────────────────────────────────────────────────────────

    @Test
    fun `consumeState resets uiState to Idle`() =
        runTest(testDispatcher) {
            fakeRepo.signUpResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("tok")

            viewModel.uiState.test {
                awaitItem() // Idle
                viewModel.signUp("user@example.com", "password123")
                awaitItem() // Loading
                advanceUntilIdle()
                awaitItem() // Success
                viewModel.consumeState()
                assertEquals(AuthUiState.Idle, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── isLoggedIn ────────────────────────────────────────────────────────────

    @Test
    fun `isLoggedIn returns false when currentUser is null`() {
        fakeRepo.currentUserValue = null
        assertTrue(!viewModel.isLoggedIn)
    }

    @Test
    fun `isLoggedIn returns true when currentUser is set`() {
        fakeRepo.currentUserValue = FakeAuthRepository.mockFirebaseUser()
        assertTrue(viewModel.isLoggedIn)
    }

    // ── signOut ───────────────────────────────────────────────────────────────

    /**
     * signOut calls repository.signOut() and resets uiState to Idle.
     *
     * Drive the VM into a non-Idle state first (Success via signIn), then
     * call signOut(). signOut() is synchronous — no coroutine launch needed,
     * so no advanceUntilIdle() call is required after signOut().
     */
    @Test
    fun `signOut calls repository signOut and resets uiState to Idle`() =
        runTest(testDispatcher) {
            fakeRepo.signInResult = Result.success(FakeAuthRepository.mockFirebaseUser())
            fakeRepo.idTokenResult = Result.success("fake-jwt-token")

            viewModel.uiState.test {
                awaitItem() // Idle

                viewModel.signIn("user@example.com", "password123")
                awaitItem() // Loading
                advanceUntilIdle()
                awaitItem() // Success

                viewModel.signOut()
                assertEquals(AuthUiState.Idle, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeRepo.signOutCallCount)
        }

    // ── Bulk validation — repo NOT called ────────────────────────────────────

    @Test
    fun `signIn repository call count is zero for all invalid inputs`() =
        runTest(testDispatcher) {
            viewModel.signIn("", "password123")
            viewModel.signIn("invalid", "password123")
            viewModel.signIn("user@example.com", "short")
            viewModel.signIn("user@example.com", "")
            assertEquals(0, fakeRepo.signInCallCount)
        }

    @Test
    fun `signUp repository call count is zero for all invalid inputs`() =
        runTest(testDispatcher) {
            viewModel.signUp("", "password123")
            viewModel.signUp("invalid", "password123")
            viewModel.signUp("user@example.com", "short")
            viewModel.signUp("user@example.com", "")
            assertEquals(0, fakeRepo.signUpCallCount)
        }

    @Test
    fun `resetPassword repository call count is zero for invalid email`() =
        runTest(testDispatcher) {
            viewModel.resetPassword("")
            viewModel.resetPassword("noatsign")
            viewModel.resetPassword("bad@")
            assertEquals(0, fakeRepo.resetCallCount)
        }
}
