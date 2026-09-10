package com.mimavault.util;

import java.util.LinkedHashSet;

/**
 * 口令短语（passphrase）内置本地词表。
 * 完全内置、不联网、无第三方依赖；词表为常见英文短词，便于记忆与听写。
 * 与安卓端 PassphraseWordList 保持一致，保证两端生成结果风格统一。
 */
public final class PassphraseWordList {

    /** 词表原始串（逗号分隔，构建时自动去重去空） */
    private static final String RAW =
            "able,acid,acorn,actor,adapt,admit,adopt,agent,alarm,album,alert,alien,alley,alpha,amber,amount,"
            + "ancient,angel,angry,ankle,answer,apple,april,arcade,archer,armor,army,arrow,artist,aspect,atom,"
            + "attic,aunt,autumn,avenue,avocado,awake,axis,bacon,badge,bagel,baker,balance,ballad,balloon,bamboo,"
            + "banana,bandit,banjo,barley,barrel,basket,beacon,beaker,beard,beaver,bench,berry,bicycle,birch,"
            + "biscuit,bishop,bison,blanket,blaze,blender,blink,blossom,boating,bonus,bottle,boulder,bouquet,bowl,"
            + "bracket,brave,bread,breeze,bridge,bright,bronze,brook,brush,bubble,bucket,buffalo,bugle,builder,"
            + "bullet,bundle,burger,butter,button,cabin,cable,cactus,camel,camera,candle,candy,canoe,canyon,"
            + "carbon,cargo,carpet,carrot,cartoon,cascade,castle,catalog,cedar,celery,cement,census,cereal,chalk,"
            + "channel,chapter,charm,cheese,cherry,chess,chicken,chili,chimney,chipmunk,chorus,cider,cinema,circle,"
            + "citrus,classic,cliff,climate,cloud,clover,cobalt,coconut,coffee,comet,compass,concert,copper,coral,"
            + "corner,cosmos,cotton,cougar,coyote,cradle,crane,crater,crayon,cream,cricket,crimson,crystal,cube,"
            + "curtain,cycle,cypress,daisy,dancer,dandelion,dawn,decoy,deer,delta,denim,desert,diamond,diesel,"
            + "digit,dinner,dolphin,domino,donut,dragon,dream,drift,drum,duck,dune,dusk,eagle,earth,ebony,echo,"
            + "eclipse,elbow,elder,ember,emerald,engine,envelope,estate,fabric,falcon,family,fantasy,farm,feather,"
            + "fence,fern,ferry,fiber,fiction,field,finch,firefly,fisher,flag,flame,flint,flower,flute,forest,"
            + "fossil,fountain,frame,fresh,friend,frost,galaxy,garden,garlic,gecko,ginger,glacier,glider,globe,"
            + "glove,gold,goose,grape,gravel,green,guitar,hammer,hammock,harbor,harvest,hazel,helmet,herb,heron,"
            + "honey,horizon,horse,hotel,humble,hunter,iceberg,igloo,image,indigo,insect,island,ivory,jacket,jade,"
            + "jaguar,jasmine,jelly,jersey,jewel,jigsaw,jungle,juniper,kangaroo,kayak,kernel,kettle,kiwi,koala,"
            + "ladder,lagoon,lake,lantern,lark,laser,latte,laurel,lavender,leaf,ledger,lemon,leopard,lettuce,level,"
            + "lilac,lily,lime,linen,lion,lizard,lobster,lotus,lumber,lunar,lyric,magnet,mango,maple,marble,market,"
            + "meadow,medal,melody,melon,mentor,mercury,meteor,midnight,mint,mirror,mission,mitten,monkey,monsoon,"
            + "moon,moss,mountain,muffin,mulberry,museum,music,mustard,napkin,nebula,nectar,needle,neon,nickel,"
            + "night,noodle,north,nova,nutmeg,oasis,ocean,olive,onion,opal,orange,orbit,orchard,orchid,organ,"
            + "osprey,otter,owl,oyster,paddle,palace,palm,panda,pansy,pantry,papaya,paper,parade,parrot,pasta,"
            + "pastel,peach,peacock,pearl,pebble,pelican,pencil,penguin,pepper,petal,photo,piano,picnic,pigeon,"
            + "pillar,pilot,pine,pioneer,piper,pixel,pizza,planet,platinum,plaza,plum,pocket,poem,polar,poodle,"
            + "poplar,poppy,portal,potato,prairie,prism,puffin,pumpkin,puzzle,pyramid,quartz,queen,quill,quilt,"
            + "rabbit,raccoon,radar,radio,radish,rainbow,raisin,ranger,raven,reef,reindeer,ribbon,rice,riddle,ring,"
            + "river,robin,rocket,roof,rosemary,rowan,royal,ruby,rudder,runner,saddle,safari,saffron,sage,salad,"
            + "salmon,salsa,sand,sapphire,sardine,satin,saxophone,scarlet,scholar,scooter,scout,scroll,season,"
            + "seaweed,sequoia,shade,shadow,shark,sheep,shell,shelter,sherbet,shield,shine,ship,shore,shrimp,"
            + "sierra,signal,silk,silver,siren,sketch,slate,sleigh,slipper,smile,smoke,snail,snow,soccer,soda,"
            + "solar,soldier,sonnet,soprano,sound,south,soybean,spark,sparrow,spice,spider,spinach,spirit,spring,"
            + "spruce,square,squid,squirrel,stable,stadium,statue,steam,steel,stellar,stone,storm,story,strawberry,"
            + "stream,sugar,summer,summit,sunbeam,sunflower,sunset,surf,swallow,swamp,sweater,swift,swing,switch,"
            + "sword,syrup,table,tablet,taco,talon,tangerine,teacup,teal,telescope,temple,tennis,tent,terrace,"
            + "thicket,thistle,thunder,tide,tiger,timber,toast,tomato,topaz,torch,tortoise,totem,tower,trail,train,"
            + "travel,treasure,trellis,triangle,trophy,trout,truck,trumpet,tulip,tundra,tunnel,turtle,tuxedo,"
            + "twilight,umbrella,unicorn,union,universe,urban,valley,vanilla,vault,velvet,vendor,venture,verse,"
            + "vessel,village,vine,violet,violin,viper,vista,volcano,voyage,waffle,wagon,walnut,walrus,waltz,"
            + "wander,wasabi,waterfall,weasel,weaver,wedge,whale,wheat,wheel,whisker,whistle,willow,window,winter,"
            + "wolf,wolverine,wombat,wonder,wool,yarrow,yeast,yellow,yonder,zebra,zenith,zephyr,zigzag,zinnia,zipper,zodiac";

    private static volatile String[] CACHE;

    private PassphraseWordList() {
    }

    /** 返回内置词表（去重后的单词数组），进程内只构建一次 */
    public static String[] words() {
        String[] cached = CACHE;
        if (cached != null) {
            return cached;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String w : RAW.split(",")) {
            String t = w.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        String[] arr = set.toArray(new String[0]);
        CACHE = arr;
        return arr;
    }

    /** 词表单词数量 */
    public static int size() {
        return words().length;
    }
}
