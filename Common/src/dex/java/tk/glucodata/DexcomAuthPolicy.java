package tk.glucodata;

/** What a G7's challenge reply (0x05, auth, bond) leads to. A bond of 3 ends the attempt before this. */
final class DexcomAuthPolicy {
    enum Next { STOP, GET_DATA, SEND_CERTIFICATES }

    private DexcomAuthPolicy() {}

    /**
     * @param androidBonded Android had bonded the device when the link came up
     * @param newCertificates this session started a fresh pairing
     * @param watchSlot the request named the smartwatch channel (3)
     */
    static Next afterChallengeReply(int auth, int bond, boolean androidBonded, boolean newCertificates, boolean watchSlot) {
        final boolean isbonded = bond == 1;
        if (watchSlot) {
            // Not authenticated on this channel: certificates sent anyway only make the
            // sensor hang up. A fresh pairing is a channel of its own, so it exchanges
            // certificates even when Android bonded the device for another one.
            if (auth != 1)
                return Next.STOP;
            return isbonded || (androidBonded && bond == 2 && !newCertificates) ? Next.GET_DATA : Next.SEND_CERTIFICATES;
        }
        // The phone app's channel: a fresh pairing goes on to the certificates.
        if (!newCertificates && auth != 1)
            return Next.STOP;
        return auth == 1 && isbonded || (androidBonded && bond == 2) ? Next.GET_DATA : Next.SEND_CERTIFICATES;
    }
}
