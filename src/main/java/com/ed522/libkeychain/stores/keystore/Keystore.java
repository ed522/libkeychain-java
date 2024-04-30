package com.ed522.libkeychain.stores.keystore;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.util.Arrays;

import com.ed522.libkeychain.stores.ChunkParser;
import com.ed522.libkeychain.stores.ObservableArrayList;

public class Keystore implements Closeable, Destroyable {

    private static final int PBKDF_ITERATIONS = 650_000;
    private static final String PBKDF_MODE = "PBKDF2WithSHA256";

    protected static final byte[] VERIFICATION_BYTES = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
    
    /*
     * Lengths may be added based on requirements.
     * 
     * Each file shall be split into *segments*, that each have their own IV.
     * Each segment is of a variable length, with a 32-byte salt at the start, a 16-byte IV and an extra 12 bytes for the AEAD tag.
     * 
     * Each segment is headed with 128 bits of random IV.
     * Segments are reencrypted upon modification and the IV is regenerated.
     * 
     * All encryption is GCM and IVs are incremented from 0.
     * Upon overflow the block is regenerated with a new salt & IV (and therefore a new block key).
     * 
     * Structure:
     * 
     * File
     *  MAGIC: "LKKS"               PLAIN       // verify that it's the right file (US ASCII)
     *  SALT: 32B                   PLAIN       // salt the password
     *  ----- everything past this is encrypted and segmented -----
     *  [[ start seg0 ]]
     *  VERIFY: 16B                 CRYPT       // test if key is correct, one block (see constant for value)
     *  ENTRIES: 8B                 CRYPT       // entry count
     *  [[ end seg0 ]]
     *  KEYS: Entry[]               CRYPT       // see entry structure, each in their own chunk
     * Entry
     *  NAMELEN: 4B                 CRYPT
     *  NAME: char[NAMELEN]         CRYPT
     *  TYPE: 1B                    CRYPT       // constant based on type (public, private, secret)
     *  ENTRYLEN: 4B                CRYPT       
     *  ENTRY: byte[ENTRYLEN]       CRYPT       // content depends on type
     */

    private final RandomAccessFile raf;
    private final SecretKey masterKey;
    private final ObservableArrayList<KeystoreEntry> entries;

    private static byte[] toBytes(int val) {
        return ByteBuffer.allocate(4).putInt(val).array();
    }
    /**
     * Build a completely new file.
     * @throws IOException 
     * @throws GeneralSecurityException
     * @throws InvalidCipherTextException 
     */
    private static void buildFile(RandomAccessFile file, String password, byte[] saltToSet) throws IOException, GeneralSecurityException {

        file.write("LKKS".getBytes(StandardCharsets.US_ASCII));

        new SecureRandom().nextBytes(saltToSet);
        file.write(saltToSet);
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF_MODE);
        SecretKey masterKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), saltToSet, PBKDF_ITERATIONS));
        
        // chunked
        ChunkParser parser = new ChunkParser(masterKey);
        // verification, 0 entries
        byte[] chunk0Bytes = Arrays.concatenate(VERIFICATION_BYTES, new byte[8]);

        file.write(parser.newChunk(chunk0Bytes));

    }
    private static void buildFile(OutputStream out, String password, byte[] saltToSet) throws IOException, GeneralSecurityException {

        out.write("LKKS".getBytes(StandardCharsets.US_ASCII));
        
        new SecureRandom().nextBytes(saltToSet);
        out.write(saltToSet);
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF_MODE);
        SecretKey masterKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), saltToSet, PBKDF_ITERATIONS));
        
        // chunked
        ChunkParser parser = new ChunkParser(masterKey);
        // verification, 0 entries
        byte[] chunk0Bytes = Arrays.concatenate(VERIFICATION_BYTES, new byte[8]);

        out.write(parser.newChunk(chunk0Bytes));

    }
    private static List<KeystoreEntry> readFile(RandomAccessFile file, String password, byte[] masterSaltToSet) throws GeneralSecurityException, IOException {

        if (masterSaltToSet.length != 32) throw new IllegalArgumentException("Bad salt length (needs to be 32)");
        
        List<KeystoreEntry> entryList = new ArrayList<>();

        byte[] magicIn = new byte[4];
        file.read(magicIn);
        // magic
        if (!new String(magicIn, StandardCharsets.US_ASCII).equals("LKKS")) throw new StreamCorruptedException("Bad magic number (wrong file?)");

        file.read(masterSaltToSet);

        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF_MODE);
        SecretKey masterKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), masterSaltToSet, PBKDF_ITERATIONS));

        // read first chunk
        ChunkParser parser = new ChunkParser(masterKey);
        ByteBuffer data = ByteBuffer.wrap(parser.decryptChunk(file));
        byte[] verification = new byte[VERIFICATION_BYTES.length];

        data.get(verification);
        if (!Arrays.areEqual(verification, VERIFICATION_BYTES)) throw new StreamCorruptedException("Bad encryption verification (wrong password?)");

        long entries = data.getLong();

        // read certificate entries

        for (int i = 0; i < entries; i++) {
            entryList.add(KeystoreEntry.parse(parser.decryptChunk(file)));
        }

        return entryList;

    }

    /**
     * Creates a {@code Keystore} for a file.
     * 
     * If you want to store to a file, USE THIS. This updates the store incrementally. However, the entire keystore will be rewritten each time it is initialized.
     * 
     * @param file The file to store. Must not be null.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public Keystore(File file, String password) throws IOException, GeneralSecurityException {
        
        if (!file.exists() && !file.createNewFile()) throw new IllegalStateException("Failed to create file");

        this.raf = new RandomAccessFile(file, "rws");
        entries = new ObservableArrayList<>(KeystoreEntry.class);
        byte[] masterSalt = new byte[32];

        // build new
        if (file.length() == 0) {
            buildFile(this.raf, password, masterSalt);
        } else {
            // read
            entries.addAll(readFile(this.raf, password, masterSalt));
        }
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF_MODE);
        masterKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), masterSalt, PBKDF_ITERATIONS));

    }

    /**
     * Creates a {@code Keystore} for the specified output stream.
     * 
     * DO NOT USE FOR FILES. This will rewrite the keystore every single time. Use the File constructor.
     * 
     * @param stream
     * @throws GeneralSecurityException 
     * @throws IOException 
     * @see Keystore#Keystore(File, String)
     */
    public Keystore(OutputStream stream, KeystoreEntry[] entries, String password) throws IOException, GeneralSecurityException {

        this.raf = null;
        this.entries = new ObservableArrayList<>(KeystoreEntry.class);
        this.entries.addOnAdd((KeystoreEntry entry) -> {
            try {
                newEntry(entry);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
        this.entries.addOnRemove((KeystoreEntry entry) -> {
            try {
                removeEntry(entry.getName());
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException(e);
            }
        });

        byte[] salt = new byte[32];

        buildFile(stream, password, salt);

        for (KeystoreEntry e : entries) {
            stream.write(e.encode());
        }
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF_MODE);
        masterKey = factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, PBKDF_ITERATIONS));

    }

    private void removeEntry(String name) throws GeneralSecurityException, IOException {

        // 1. find the entry
        // 2. shift chunks of the file back
        // 3. truncate file

        long initial = raf.getFilePointer();

        /* find entry */
        raf.seek(122); // skip master salt and seg0

        long entryOffset = -1;
        long size = -1;
        ChunkParser parser = new ChunkParser(masterKey);
        
        while (true) {

            entryOffset = raf.getFilePointer();
            byte[] chunk = parser.decryptChunk(raf);
            KeystoreEntry entry = KeystoreEntry.parse(chunk);
            size = chunk.length + 52l /* extra data */;

            if (entry.getName().equals(name)) break;

        }

        if (entryOffset == -1 || size == -1) throw new NoSuchElementException("No entry with name in file");

        /* shift chunks */
        while (raf.getFilePointer() < raf.length()) {

            long initialOffset = raf.getFilePointer();
            int cLen = parser.chunkLength(raf);
            byte[] chunk = new byte[cLen + 52];
            raf.seek(initialOffset - size);
            raf.write(chunk);

        }

        /* shrink */
        raf.setLength(raf.length() - size);

        // return
        raf.seek(initial);

    }

    private void newEntry(KeystoreEntry entry) throws IOException, GeneralSecurityException {

        raf.seek(raf.length());
        new ChunkParser(masterKey).newChunk(entry.encode(), raf);

    }

    public void add(KeystoreEntry entry) {
        this.entries.add(entry);
    }
    public PrivateKey getPrivate(String name) {
        for (KeystoreEntry entry : entries) {
            if (entry.getName().equals(name) && entry.getType().equals(EntryType.PRIVATE)) return entry.getPrivate();
        }
        return null;
    }
    public Certificate getCertificate(String name) {
        for (KeystoreEntry entry : entries) {
            if (entry.getName().equals(name) && entry.getType().equals(EntryType.PUBLIC)) return entry.getCertificate();
        }
        return null;
    }
    public SecretKey getSecret(String name) {
        for (KeystoreEntry entry : entries) {
            if (entry.getName().equals(name) && entry.getType().equals(EntryType.SECRET)) return entry.getSecret();
        }
        return null;
    }
    public void remove(String name, EntryType type) {
        for (int i = 0; i < entries.size(); i++) {
            KeystoreEntry e = entries.get(i);
            if (e.getName().equals(name) && e.getType().equals(type)) entries.remove(i);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Keystore ks) {
            return ks.entries.equals(this.entries) && Arrays.areEqual(this.masterKey.getEncoded(), ks.masterKey.getEncoded());
        } else return false;
    }

    @Override
    public int hashCode() {
        // use a true hash bc the key is sensitive
        // however collisions aren't an issue so 32b will be fine
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA256");
            digest.update(toBytes(entries.hashCode()));
            digest.update(masterKey.getEncoded());
            digest.update(toBytes(raf.hashCode()));
            return ByteBuffer.wrap(digest.digest()).getInt();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        this.raf.close();
        try {
            this.masterKey.destroy();
        } catch (DestroyFailedException e) {
            // disregard failed destroy, this is a close
        }
    }

    @Override
    public void destroy() throws DestroyFailedException {
        this.masterKey.destroy();
    }

}
