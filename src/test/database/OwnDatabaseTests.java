package database;

import database.owndatabase.ChangeTrackingSet;
import database.owndatabase.DataAccess;
import database.owndatabase.DatabaseDiskPersistence;
import database.owndatabase.PureMemoryDatabase;
import logging.TestLogger;
import primary.dataEntities.TestThing;
import primary.dataEntities.TestThing2;
import utils.ExtendedExecutor;
import utils.FileUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static framework.TestFramework.assertEquals;
import static framework.TestFramework.assertTrue;
import static utils.Crypto.*;

/**
 * Tests for our own database
 */
public class OwnDatabaseTests {
    private final TestLogger logger;

    public OwnDatabaseTests(TestLogger logger) {
        this.logger = logger;
    }

    /*
    We'll use this to drive the initial creation of our database.  Intent here
    is to keep things as simple as possible.  Initial design is to make a
    pure memory-based database with occasional file writing, directly
    controlled through Java (that is, no need for intermediating SQL language).
     */
    public void tests(ExecutorService es) throws Exception {


        /*
         * One aspect we need to control is to have the ability to write and
         * read to individual blocks within a file.  Back when I developed r3z,
         * the database that was built stored each piece of data in its own
         * file so that reads and writes would not take so long.  The thinking
         * was that a read from any particular file would be as quick
         * as possible because of how little data existed in that file.  Also,
         * that we could rely on the OS file system for easy access to various pieces of
         * data.
         *
         * It does appear that it should be possible to write and read to individual blocks
         * inside a file, so that we don't need to have (literally) millions of files
         * (e.g. in the case where we have millions of individual pieces of data).
         *
         * Let's try it out.
         *
         */
        logger.test("Create a file, write to parts of it, read parts of it");
        {
            final var filePath = Path.of("a_test_file");
            try {
                final var path = Files.createFile(filePath);
                final var bw = new BufferedWriter(new FileWriter(path.toFile()));
                // make a file
                final var fileSize = 10;
                for (int i = 0; i < fileSize; i++) {
                    bw.write(0x0);

                }
                bw.flush();
                final var earlier = System.currentTimeMillis();
                final var raf = new RandomAccessFile(path.toFile(), "rws");
                for (int i = 0; i < 5; i++) {
                    final var pos = fileSize / 2;
                    raf.seek(pos);
                    raf.write('c');
                }
                final var later = System.currentTimeMillis();
                raf.seek(fileSize);
                // write after the end of the file
                raf.write("this is a test".getBytes(StandardCharsets.UTF_8));
                System.out.println(later - earlier);
            } finally {
                Files.delete(filePath);
            }
        }


        /*
         * The question is: what kind of data structure should we use?  Let's assume
         * we'll primarily keep our data in memory for this database and occasionally
         * write to disk.  If we make good choices about our data and don't just
         * stuff everything in there, this should work for most situations.
         *
         * If we use something like a hashmap, we'll have O(1) speed for access, and
         * if we can serialize to a form that lets us make block-level edits to a file,
         * we can save quickly.
         *
         * What's the simplest approach?
         */
        logger.test("serialize some data");
        {
            record Thingamajig(String name, String favoriteColor, String favoriteIceCream) {}

            // let's say we have a hash map.  that's what we'll keep in
            // raw array
            // hash map
            // vectors
            // list
            // if we want to have random access into a file, we'll need to basically control all
            // the data, byte for byte, precisely, right?

            final var foo = new HashMap<Integer, Thingamajig>();
        }

        /*
        Like, fo' sha

        Tested out a few.
        SHA-1: 110 millis
        SHA-256: 90 millis
        SHA-512: 150 millis

        I guess we'll go with sha-256
         */
//        logger.test("how fast is sha");
//        {
//            final var earlier = System.currentTimeMillis();
//            for (var i = 0; i < 100_000; i++) {
//                hashStringSha256("hello");
//            }
//            final var later = System.currentTimeMillis();
//            System.out.println("sha256 took " + (later - earlier) + " millis for 100,000 cycles");
//        }

        logger.test("playing with getting a base64 value from a hash sha-256");
        {
            final var encodedhash = bytesToHex(hashStringSha256("hello"));
            assertEquals(encodedhash, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        }

        logger.test("playing with getting a base64 value from a hash sha-1");
        {
            final var encodedhash = bytesToHex(hashStringSha1("hello"));
            assertEquals(encodedhash, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d");
        }

        logger.test("starting to craft a memory database similar to r3z");
        {
            /*
              start with a basic hashmap, with a string as the key being
              effectively the name of the table, and then the value being
              a list, effectively the "rows" of the table.
             */
            final var db = new HashMap<String, List<?>>();

            record Thing(int a, String b){}

            final var a_list_of_things = List.of(new Thing(42, "the meaning of life"));

            // add something to the database
            db.put("thing", a_list_of_things);

            // add another thing
            final var a_new_thing = new Thing(1, "one is the loneliest number");
            final var concatenatedList = Stream.concat(a_list_of_things.stream(), Stream.of(a_new_thing)).toList();
            db.put("thing", concatenatedList);

            // get all the things
            final var things = db.get("thing");
            assertEquals(things.toString(), "[Thing[a=42, b=the meaning of life], Thing[a=1, b=one is the loneliest number]]");
        }

        /*
         * The database needs to use generics a bit to have some type-safety,
         * since each of its lists will be of a different type - that is, we
         * may have a list of dogs, of cars, of bicycles ...
         */
        logger.test("Creating a generic method for the database");
        {
            record Thing(int a, String b){}
            final var db = Database.createDatabase();
            db.createNewList("things", Thing.class);
            final Database.DbList<Thing> things = db.getList("things", Thing.class);
            things.actOn(t -> t.add(new Thing(42, "the meaning of life")));
            final var result = things.read(t -> t.stream().filter(x -> x.b.equals("the meaning of life")).toList());
            assertEquals(result.get(0).a, 42);
        }

        /*
         * If we previously registered some data as "Thing" and we subsequently
         * ask for it as type "Other", we should get a complaint
         */
        logger.test("Testing the type safety");
        {
            record Thing(int a, String b){}
            record Other(int a, String b){}
            final var db = Database.createDatabase();
            db.createNewList("things", Thing.class);
            try {
                db.getList("things", Other.class);
            } catch (RuntimeException ex) {
                assertEquals(ex.getMessage(), "It should not be possible to have multiple matching keys here. " +
                        "keys: " +
                        "[NameAndType[name=things, clazz=class database.OwnDatabaseTests$2Thing], " +
                        "NameAndType[name=things, clazz=class database.OwnDatabaseTests$3Thing]]");
            }
        }

        /*
        Here, I'm wondering whether I can create separate data structures
        that cover the data in others.  For example, if I create a list of a, b, c, can I
        create a map that contains those values?

        This is valuable in the case of our home-cooked database.  The data is stored in
        sets, so if I am running a query that accesses data by identifier, it has to loop
        through all the data.  If instead I create a separate hash map for the same data,
        then I suddenly gain all the speed of a SQL indexed table lookup.
         */
        logger.test("can I have a hashmap that refers to the same objects in a list?");
        {
            // short answer: yes.  Longer answer: see below.
            final var listOfStuff = List.of(new Object(), new Object());

            Map<Integer, Object> myMap = new HashMap<>();
            var index = 0;
            for (var item : listOfStuff) {
                myMap.put(index, item);
                /*
                check to make sure the objects being pointed to are
                the same (note that unlike the normal case where we
                compare values, here I'm specifically concerned
                that the items in question are pointing to the
                exact same object in memory
                 */
                assertTrue(item == myMap.get(index));
                index += 1;
            }

        }

        logger.test("add and get data from the database");
        {
            Map<String, ChangeTrackingSet<?>> data = new HashMap<>();
            data.put(TestThing.INSTANCE.getDataName(), new ChangeTrackingSet<>());
            final var db = new PureMemoryDatabase(null, data, logger);
            final DataAccess<TestThing> testThingDataAccess = db.dataAccess(TestThing.INSTANCE.getDataName());
            final var enteredThing = new TestThing(123);
            testThingDataAccess.actOn(x -> x.add(enteredThing));
            final var foundValue = testThingDataAccess.read(x -> x.stream().filter(y -> y.getIndex() == 123)).findFirst().orElseThrow();
            assertEquals(foundValue, enteredThing);
        }

        logger.test("playing with an entity - it should be able to serialize itself");
        {
            final var thing = new TestThing(123);
            final var serialized = thing.serialize();
            assertEquals("{ id: 123 }", serialized);
            assertEquals(thing, TestThing.INSTANCE.deserialize(serialized));
        }

        logger.test("playing with a new entity to make sure we've abstracted properly");
        {
            final var thing = new TestThing2(123, "blue", "vanilla");
            final var serialized = thing.serialize();
            assertEquals("{ c: blue , ic: vanilla , id: 123 }", serialized);
            assertEquals(thing, TestThing2.INSTANCE.deserialize(serialized));
        }

        logger.test("playing with a database that uses disk persistence");
        {
            // clean the old database in preparation for this test
            FileUtils.deleteDirectoryWithFiles(Path.of("out/db"));

            Map<String, ChangeTrackingSet<?>> data = new HashMap<>();
            data.put(TestThing.INSTANCE.getDataName(), new ChangeTrackingSet<>());
            final var ddp = new DatabaseDiskPersistence("out/db", es, logger);
            final var db = new PureMemoryDatabase(ddp, data, logger);
            final DataAccess<TestThing> testThingDataAccess = db.dataAccess(TestThing.INSTANCE.getDataName());
            final var enteredThing = new TestThing(123);
            testThingDataAccess.actOn(x -> x.add(enteredThing));
            final var foundValue = testThingDataAccess.read(x -> x.stream().filter(y -> y.getIndex() == 123)).findFirst().orElseThrow();
            assertEquals(foundValue, enteredThing);

            // wait a tiny bit of time for the data to become written to disk; the next test needs it there
            // note that the database we're writing is *eventually* written to disk.  That means if you create
            // new data it's instantly available for the next call but it might not yet be on the disk.
            // The next test starts out by reading data from the disk - the data written during this test.
            Thread.sleep(50);
        }

        logger.test("the database should read its data at startup from the disk");
        {
            // create the persistence class
            final var ddp = new DatabaseDiskPersistence("out/db", es, logger);

            // create the schema map - a map between the name of a datatype to its data
            // note that we are deserializing some data from previous tests here (the data
            // was written to disk in a previous test, we're reading it in now)
            Map<String, ChangeTrackingSet<?>> schema = new HashMap<>();
            ddp.updateSchema(schema, TestThing.INSTANCE);
            ddp.updateSchema(schema, TestThing2.INSTANCE);

            // create the database instance and the data accessors
            final var db = new PureMemoryDatabase(ddp, schema, logger);
            final DataAccess<TestThing> testThingDataAccess = db.dataAccess(TestThing.INSTANCE.getDataName());
            final DataAccess<TestThing2> testThing2DataAccess = db.dataAccess(TestThing2.INSTANCE.getDataName());

            // work with the data accessors to get and create data
            final var foundValue = testThingDataAccess.read(x -> x.stream().filter(y -> y.getIndex() == 123)).findFirst().orElseThrow();
            assertEquals(foundValue, new TestThing(123));
            final var testThing2 = new TestThing2(42, "blue", "vanilla");
            testThing2DataAccess.actOn(x -> x.add(testThing2));
            final var foundValue2 = testThing2DataAccess.read(x -> x.stream().filter(y -> y.getIndex() == 42)).findFirst().orElseThrow();
            assertEquals(foundValue2, testThing2);
        }

        logger.test("playing around with other functions of a database - deleting, updating");
        {
            // create the persistence class
            final var ddp = new DatabaseDiskPersistence("out/db", es, logger);
            final var schema = ddp.createInitialEmptyMap();

            // go through some edge conditions:
            // ----------------------------------------------------------
            // 1. a file exists for a type of data, but nothing is in it
            // ----------------------------------------------------------

            Files.writeString(Path.of("out/db/TestThing2/bad.db"), "");
            ddp.updateSchema(schema, TestThing2.INSTANCE);
            Files.deleteIfExists(Path.of("out/db/TestThing2/bad.db"));


            // create the database instance and the data accessors
            final var db = new PureMemoryDatabase(ddp, schema, logger);
            final DataAccess<TestThing2> testThing2DataAccess = db.dataAccess(TestThing2.INSTANCE.getDataName());

            // ----------------------------------------------------------
            // 2. updating some data
            // ----------------------------------------------------------
            final var testThing2 = testThing2DataAccess.read(x -> x.stream().findFirst()).orElseThrow();
            testThing2DataAccess.actOn(x -> x.update(new TestThing2(testThing2.getIndex(), "red", "chocolate")));

            // ----------------------------------------------------------
            // 3. a binary data file existing where we expect a textual file
            // ----------------------------------------------------------

            try {
                Files.write(Path.of("out/db/TestThing2/bad.db"), new byte[]{1, 2, 3});
                ddp.updateSchema(schema, TestThing2.INSTANCE);
            } catch (RuntimeException e) {
                assertEquals(e.getMessage(), "Failed to deserialize out/db/TestThing2/bad.db with data (\u0001\u0002\u0003)");
            }
            Files.deleteIfExists(Path.of("out/db/TestThing2/bad.db"));

            // ----------------------------------------------------------
            // 4. deleting existing data
            // ----------------------------------------------------------
            testThing2DataAccess.actOn(x -> x.remove(testThing2));

            // ----------------------------------------------------------
            // 5. trying to delete data when it is gone
            // ----------------------------------------------------------
            testThing2DataAccess.actOn(x -> x.remove(testThing2));

        }

        logger.test("causing a NoSuchFileException in the ActionQueue");
        {

            // ----------------------------------------------------------
            // 6. trying to delete data when it exists in memory but file was deleted
            // ----------------------------------------------------------
            // note: this is for our information only.  If someone is going around wrecking
            // the underlying data files while the program is running, cannot really
            // recover from that kind of sabotage in a reasonable way.
            // create the persistence class

            final var myLocalEs = Executors.newCachedThreadPool();
            final var myLocalLogger = new TestLogger(myLocalEs);
            final var ddp = new DatabaseDiskPersistence("out/db", myLocalEs, myLocalLogger);
            final var schema = ddp.createInitialEmptyMap();
            ddp.updateSchema(schema, TestThing2.INSTANCE);
            final var db = new PureMemoryDatabase(ddp, schema, logger);
            final DataAccess<TestThing2> testThing2DataAccess = db.dataAccess(TestThing2.INSTANCE.getDataName());

            final var testThing2_again = new TestThing2(1234, "orange", "vanilla");
            testThing2DataAccess.actOn(x -> x.add(testThing2_again));
            // give a little time for the write to happen before the next step
            Thread.sleep(10);
            Files.delete(Path.of("out/db/TestThing2/1234.db"));
            Thread.sleep(10);

            testThing2DataAccess.actOn(x -> x.remove(testThing2_again));
            try {
                ddp.getActionQueue().getPrimaryFuture().get();
            } catch (ExecutionException ex) {
                assertEquals(ex.getMessage(), "java.lang.RuntimeException: java.nio.file.NoSuchFileException: out/db/TestThing2/1234.db");
            }
            myLocalEs.shutdownNow();
        }

        logger.test("A couple more database edge cases");
        {
            // create the persistence class
            final var ddp = new DatabaseDiskPersistence("out/db", es, logger);
            final var schema = ddp.createInitialEmptyMap();
            ddp.updateSchema(schema, TestThing2.INSTANCE);
            // create the database instance and the data accessors
            final var db = new PureMemoryDatabase(ddp, schema, logger);
            final DataAccess<TestThing2> testThing2DataAccess = db.dataAccess(TestThing2.INSTANCE.getDataName());

            // create some initial data
            final var testThing2 = new TestThing2(456, "something", "or other");
            testThing2DataAccess.actOn(x -> x.add(testThing2));

            // ----------------------------------------------------------
            // 6. trying to delete data when the file it connects to is locked
            // NOTE: ***** THE FILE LOCK DOES NOT PREVENT US DELETING THE FILE ***
            // as you can see if you run this, we can still delete the file
            // ----------------------------------------------------------
            new RandomAccessFile("out/db/TestThing2/456.db","rw").getChannel().lock();
            testThing2DataAccess.actOn(x -> x.remove(testThing2));

        }
    }
}
