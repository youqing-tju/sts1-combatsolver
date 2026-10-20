public final class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("[probe] java.version      = " + System.getProperty("java.version"));
        System.out.println("[probe] class file major  = " + System.getProperty("java.class.version"));

        check("lib/desktop-1.0.jar", "com/megacrit/cardcrawl/dungeons/AbstractDungeon.class");
        check("lib/desktop-1.0.jar", "com/megacrit/cardcrawl/random/Random.class");
        check("lib/ModTheSpire.jar", "com/evacipated/cardcrawl/modthespire/lib/SpireInitializer.class");

        System.out.println("[probe] BUILD-CHAIN OK");
    }

    private static void check(String jar, String entry) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
        try {
            boolean found = zf.getEntry(entry) != null;
            System.out.println("[probe] " + (found ? "found   " : "MISSING ") + entry + "  <- " + jar);
            if (!found) {
                throw new IllegalStateException("缺少类：" + entry + " in " + jar);
            }
        } finally {
            zf.close();
        }
    }
}
