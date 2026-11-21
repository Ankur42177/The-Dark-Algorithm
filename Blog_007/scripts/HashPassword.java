import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Prints the {bcrypt} hash of a password read from stdin. Used by hash-password.sh. */
public class HashPassword {
    public static void main(String[] args) throws Exception {
        String pw = new String(System.in.readAllBytes()).strip();
        if (pw.length() < 12) {
            System.err.println("Use at least 12 characters.");
            System.exit(1);
        }
        System.out.println(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(pw));
    }
}
