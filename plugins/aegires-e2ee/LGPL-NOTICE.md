# Third-party code under the LGPL

`src/main/java/aegires/e2ee/Crypto.java`, `Argon2id.java` and `Blake2b.java` are copied unchanged (except
the `package` line and, in Crypto.java, the `android.util.Base64` import, which `Base64.java` in the same
folder replaces). They are refreshed with `tools/sync-aegires-java.sh` from the Aegires Android app, https://github.com/Legitbhaidark/Aegires, which is
licensed under the GNU Lesser General Public License v3.0 (https://www.gnu.org/licenses/lgpl-3.0.html).

These files stay under the LGPL v3. Everything else in this plugin keeps the licence in LICENSE.md.
Their source is in this repository, so you can modify them and rebuild the plugin.
