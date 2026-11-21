package app.phoneproof.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature

class MeetupCodecTest {

    @Test fun challengeRoundTrip() {
        val c = MeetupCodec.newChallenge("0011223344556677")
        assertEquals(c, MeetupCodec.parseChallenge(MeetupCodec.encodeChallenge(c)))
        assertEquals(null, MeetupCodec.parseChallenge("pp1:c:short:-"))
        assertEquals(null, MeetupCodec.parseChallenge("https://evil.example/"))
    }

    @Test fun framesReassembleInAnyOrderAndVerify() {
        val chain = FakeAttestation.chain()
        val ch = MeetupCodec.newChallenge(null)
        val p = ReportPayload(ReportKind.MEETUP, "1.0", 1, 1L, DeviceClaims("google", "Pixel 7", "Google", 35, "2026-09-05"),
            Encoding.keyId(chain.certs[0].publicKey.encoded), ch.nonce,
            listOf(TestResult("display.touch", TestOutcome.PASS, DecidedBy.AUTO, mapOf("cells" to 240L, "missed" to 0L))))
        val json = p.toJson()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(chain.leafKey.private); update(CanonicalJson.encodeToBytes(json)); sign() }
        val env = ReportEnvelope(json, Encoding.b64url(sig), chain.certs.map { Encoding.b64(it.encoded) })

        val frames = MeetupCodec.frames(env.encode())
        assertTrue("frames=${frames.size}", frames.size in 2..10)
        assertTrue(frames.all { it.length <= MeetupCodec.CHUNK + 40 })
        val r = MeetupCodec.Reassembler()
        frames.reversed().forEach { r.add(it); r.add(it) } // duplicates and reverse order
        val back = ReportEnvelope.decode(r.result!!)
        val cfg = VerifierConfig("app.phoneproof", setOf(Encoding.hex(ByteArray(32) { 7 })), listOf(chain.root), setOf("x"))
        val now = System.currentTimeMillis()
        val v = ReportVerifier(cfg).verify(back, now, MeetupExpectation(ch.nonce, now - 30_000, null))
        assertEquals(CheckStatus.OK, v.checks.first { it.code == "nonce" }.status)
        assertTrue(v.verdict != Verdict.RED)
    }

    @Test fun rejectsGarbageFrames() {
        val r = MeetupCodec.Reassembler()
        assertTrue(!r.add("hello"))
        assertTrue(!r.add("pp1:f:9/3:abcd1234:xx"))
    }
}
