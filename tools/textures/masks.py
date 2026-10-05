"""
Themed Cosmic masks: a 16x16 inventory icon and a 3D model for the head (worn), per theme. The worn model is a face
plate in front of the face (also in front of a helmet) plus theme parts - a rooster's beak, wattle, comb and tail for
the Turkey Mask, flames and goggles for the Nitro Mask, a top hat and beard for the Lucky Leprechaun Mask ...

Model space: the head is the cube 1.6 .. 14.4 on every axis (worn items are drawn at 0.625 scale around the head
centre), north (-z) is the face. Textures (item atlas, textures/item/prisons/mask_worn): 32x32, face art in the top-left 16x16, colour swatches (4x4) on the right.
"""
import json
import os

from PIL import Image

OUT = os.path.join("src", "main", "resources", "assets", "theprisons")

O = (0x14, 0x12, 0x1C, 255)  # outline
CLEAR = (0, 0, 0, 0)

# theme: palette (char -> colour), icon rows, face rows (16x16 art on the plate), parts [(from, to, colour char, rotation)]
THEMES = {
    "turkey": {
        "palette": {"o": O, "b": (0x8A, 0x55, 0x2E, 255), "B": (0x5E, 0x37, 0x1C, 255), "w": (0xF4, 0xEE, 0xE0, 255),
                    "k": (0x10, 0x10, 0x14, 255), "y": (0xFF, 0xC2, 0x2E, 255), "Y": (0xE0, 0x8A, 0x1C, 255),
                    "r": (0xE8, 0x2A, 0x3A, 255), "R": (0xA8, 0x16, 0x26, 255), "g": (0x2E, 0x8A, 0x5A, 255)},
        "icon": [
            "......r.r.......",
            ".....rrrrr......",
            "....orrrrro.....",
            "...obbbbbbbo....",
            "..obbwwbbbbbo...",
            "..obbwkbbbbbo...",
            ".oobbbbbbbbbbo..",
            "yyyobbbbbbbbbo..",
            "YyyybbbbbbbbBo..",
            ".yyobbbbbbbbBo..",
            "..oRRbbbbbbBBo..",
            "..oRRobbbbBBo...",
            "...RR.oBBBBo....",
            "......ooooo.....",
            "................",
            "................",
        ],
        "face": [
            "bbbbbbbbbbbbbbbb",
            "bBbbbbbbbbbbbbBb",
            "bbbbbbbbbbbbbbbb",
            "bbwwwwbbbbwwwwbb",
            "bwwkkwwbbwwkkwwb",
            "bwwkkwwbbwwkkwwb",
            "bbwwwwbbbbwwwwbb",
            "bbbbbbbbbbbbbbbb",
            "bbbbbbbbbbbbbbbb",
            "bBbbbbbbbbbbbbBb",
            "bbbbbbbbbbbbbbbb",
            "bbBbbbbbbbbbBbbb",
            "bbbbbbbbbbbbbbbb",
            "bBbbbbbbbbbbbbBb",
            "bbbbbbbbbbbbbbbb",
            "BBBBBBBBBBBBBBBB",
        ],
        "plate": "b",
        "parts": [
            ([6.5, 6.5, -3.6], [9.5, 8.5, -1.2], "y", None),        # beak
            ([7.0, 6.0, -4.6], [9.0, 7.5, -3.6], "Y", None),        # beak tip
            ([7.0, 3.0, -2.4], [9.0, 6.5, -1.2], "r", None),        # wattle
            ([7.0, 14.4, 2.0], [9.0, 17.0, 10.0], "r", None),       # comb
            ([7.0, 17.0, 3.0], [9.0, 18.6, 5.0], "R", None),
            ([7.0, 17.0, 6.0], [9.0, 19.2, 8.0], "R", None),
            ([6.5, 9.0, 14.4], [9.5, 22.0, 15.6], "b", None),       # tail fan
            ([6.5, 9.0, 14.4], [9.5, 21.0, 15.6], "Y", (8, 9, 15, "z", 22.5)),
            ([6.5, 9.0, 14.4], [9.5, 21.0, 15.6], "Y", (8, 9, 15, "z", -22.5)),
            ([6.5, 9.0, 14.6], [9.5, 19.0, 15.8], "r", (8, 9, 15, "z", 45)),
            ([6.5, 9.0, 14.6], [9.5, 19.0, 15.8], "r", (8, 9, 15, "z", -45)),
        ],
    },
    "nitro": {
        "palette": {"o": O, "s": (0x22, 0x24, 0x30, 255), "S": (0x3A, 0x3E, 0x50, 255), "c": (0x3C, 0xE8, 0xFF, 255),
                    "C": (0xB8, 0xF6, 0xFF, 255), "f": (0xFF, 0x6A, 0x1E, 255), "y": (0xFF, 0xD8, 0x3C, 255),
                    "r": (0xE8, 0x2A, 0x3A, 255), "w": (0xF4, 0xF6, 0xFA, 255)},
        "icon": [
            "....f...f...f...",
            "...fyf.fyf.fyf..",
            "...fyyffyyffyyf.",
            "..oooooooooooo..",
            ".oSSSSSSSSSSSSo.",
            ".osssssssssssso.",
            "oscCCcsssscCCcso",
            "osccccssssccccso",
            ".osssssssssssso.",
            ".oswwssssssrrso.",
            ".osssssssssssso.",
            "..osfffffffffo..",
            "...ofyyyyyyfo...",
            "....oooooooo....",
            "................",
            "................",
        ],
        "face": [
            "ssssssssssssssss",
            "sSSSSSSSSSSSSSSs",
            "ssssssssssssssss",
            "scccccsssscccccs",
            "scCCccsssscCCccs",
            "scccccsssscccccs",
            "ssssssssssssssss",
            "swwwwssssssrrrrs",
            "ssssssssssssssss",
            "ssssssssssssssss",
            "ssssssssssssssss",
            "sffffffffffffffs",
            "sfyyyyfffyyyyyfs",
            "ffyyyyyyyyyyyyff",
            "fyyyyyyyyyyyyyyf",
            "yyyyyyyyyyyyyyyy",
        ],
        "plate": "s",
        "parts": [
            ([2.5, 8.5, -2.4], [6.5, 11.5, -1.2], "c", None),       # goggles
            ([9.5, 8.5, -2.4], [13.5, 11.5, -1.2], "c", None),
            ([3.0, 14.4, 3.0], [5.0, 18.0, 11.0], "f", (4, 14.4, 7, "x", -22.5)),   # flames, swept back
            ([7.0, 14.4, 2.0], [9.0, 19.5, 12.0], "y", (8, 14.4, 7, "x", -22.5)),
            ([11.0, 14.4, 3.0], [13.0, 18.0, 11.0], "f", (12, 14.4, 7, "x", -22.5)),
            ([0.6, 7.0, 4.0], [1.6, 10.0, 13.0], "c", None),        # speed fins
            ([14.4, 7.0, 4.0], [15.4, 10.0, 13.0], "c", None),
        ],
    },
    "outpost": {
        "palette": {"o": O, "g": (0x5E, 0x6E, 0x4A, 255), "G": (0x3E, 0x4A, 0x30, 255), "m": (0x9C, 0xA2, 0xAC, 255),
                    "M": (0x5A, 0x60, 0x6C, 255), "l": (0xFF, 0xB8, 0x3C, 255), "k": (0x14, 0x16, 0x1C, 255),
                    "r": (0xE8, 0x2A, 0x3A, 255)},
        "icon": [
            "............oo..",
            "............oMo.",
            "...oooooooooomo.",
            "..ogggggggggggo.",
            ".ogGGGGGGGGGGGgo",
            ".ogllgggggggllgo",
            ".ogllgggggggllgo",
            ".ogggggggggggggo",
            ".oggggmmmmggggo.",
            "..oggmMMMMmggo..",
            "..oggmMkkMmggo..",
            "...ogmMMMMmgo...",
            "....oommmmoo....",
            "......oooo......",
            "................",
            "................",
        ],
        "face": [
            "gggggggggggggggg",
            "gGGGGGGGGGGGGGGg",
            "gggggggggggggggg",
            "gklllkggggklllkg",
            "gkllllkggkllllkg",
            "gklllkggggklllkg",
            "gggggggggggggggg",
            "gggggggggggggggg",
            "ggggggmmmmgggggg",
            "gggggmMMMMmggggg",
            "gggggmMkkMmggggg",
            "gggggmMMMMmggggg",
            "ggggggmmmmgggggg",
            "gggggggggggggggg",
            "gGgGgGgGgGgGgGgG",
            "GGGGGGGGGGGGGGGG",
        ],
        "plate": "g",
        "parts": [
            ([5.5, 2.5, -3.8], [10.5, 6.5, -1.2], "M", None),       # filter canister
            ([6.5, 3.5, -4.4], [9.5, 5.5, -3.8], "k", None),
            ([12.0, 14.4, 9.0], [13.0, 21.0, 10.0], "M", None),     # antenna
            ([11.8, 20.6, 8.8], [13.2, 21.6, 10.2], "r", None),
            ([0.8, 9.5, 1.0], [15.2, 10.5, 15.2], "G", None),       # strap
        ],
    },
    "leprechaun": {
        "palette": {"o": O, "g": (0x2E, 0xB8, 0x4A, 255), "G": (0x1C, 0x7A, 0x30, 255), "a": (0xFF, 0xD2, 0x3C, 255),
                    "k": (0x14, 0x14, 0x18, 255), "s": (0xF2, 0xC4, 0x9C, 255), "S": (0xD8, 0x9C, 0x74, 255),
                    "r": (0xF0, 0x7A, 0x2A, 255), "R": (0xC0, 0x56, 0x1A, 255), "w": (0xF4, 0xF2, 0xEC, 255)},
        "icon": [
            ".....oooooo.....",
            ".....oggggo.....",
            ".....oggggo.....",
            ".....okakko.....",
            "..oooooooooooo..",
            "..oGGGGGGGGGGo..",
            "...ossssssssso..",
            "...oswksswkso...",
            "...osssSSssso...",
            "..orsssssssro...",
            "..orrsrrrsrro...",
            "..orrrrrrrrro...",
            "...orrrrrrro....",
            "....orrrrro.....",
            ".....ooooo......",
            "................",
        ],
        "face": [
            "ssssssssssssssss",
            "ssssssssssssssss",
            "ssssssssssssssss",
            "sswwwsssssswwwss",
            "sswkwsssssswkwss",
            "ssssssssssssssss",
            "sSssssssssssssSs",
            "sssssssSSsssssss",
            "rssssssSSssssssr",
            "rrssssssssssssrr",
            "rrrsssRRRRsssrrr",
            "rrrrrrrrrrrrrrrr",
            "rrrRrrrrrrrrRrrr",
            "rrrrrrrrrrrrrrrr",
            "rRrrrrRrrRrrrrRr",
            "rrrrrrrrrrrrrrrr",
        ],
        "plate": "s",
        "parts": [
            ([0.6, 14.4, 0.6], [15.4, 15.4, 15.4], "G", None),      # hat brim
            ([3.0, 15.4, 3.0], [13.0, 22.0, 13.0], "g", None),      # hat crown
            ([2.9, 15.4, 2.9], [13.1, 16.8, 13.1], "k", None),      # band
            ([6.5, 15.3, 2.4], [9.5, 17.0, 2.9], "a", None),        # buckle
            ([1.6, -0.4, -2.0], [14.4, 5.0, 3.0], "r", None),       # beard
            ([4.0, -2.4, -1.6], [12.0, -0.4, 2.0], "R", None),
        ],
    },
    "valor": {
        "palette": {"o": O, "m": (0xC8, 0xCE, 0xD8, 255), "M": (0x86, 0x8C, 0x9A, 255), "k": (0x14, 0x14, 0x1A, 255),
                    "a": (0xFF, 0xD2, 0x3C, 255), "r": (0xE0, 0x24, 0x36, 255), "R": (0x9A, 0x14, 0x22, 255)},
        "icon": [
            "......rrr.......",
            ".....rrRrr......",
            "......oRo.......",
            "...oooooooooo...",
            "..ommmmmmmmmMo..",
            "..ommmmammmmMo..",
            "..okkkkakkkkko..",
            "..ommmmammmmMo..",
            "..okkkkakkkkko..",
            "..ommmmammmmMo..",
            "..ommmMamMmmMo..",
            "..oMmMMaMMmMMo..",
            "...oMMMMMMMMo...",
            "....oooooooo....",
            "................",
            "................",
        ],
        "face": [
            "mmmmmmmmmmmmmmmm",
            "mmmmmmmmmmmmmmmm",
            "mmmmmmmammmmmmmm",
            "mmmmmmmammmmmmmm",
            "kkkkkkkakkkkkkkk",
            "kkkkkkkakkkkkkkk",
            "mmmmmmmammmmmmmm",
            "mkmkmkmamkmkmkmm",
            "mmmmmmmammmmmmmm",
            "mkmkmkmamkmkmkmm",
            "mmmmmmmammmmmmmm",
            "MmmmmmmammmmmmmM",
            "MMmmmmmammmmmmMM",
            "MMMmmmmammmmmMMM",
            "MMMMMMMaMMMMMMMM",
            "MMMMMMMMMMMMMMMM",
        ],
        "plate": "m",
        "parts": [
            ([7.4, 2.0, -2.4], [8.6, 14.0, -1.2], "a", None),       # nose guard
            ([7.0, 14.4, 1.0], [9.0, 21.0, 11.0], "r", (8, 14.4, 6, "x", -22.5)),   # plume
            ([7.2, 16.0, 9.0], [8.8, 20.0, 14.0], "R", (8, 14.4, 6, "x", -22.5)),
            ([0.6, 6.0, 1.0], [1.6, 14.4, 12.0], "M", None),        # cheek plates
            ([14.4, 6.0, 1.0], [15.4, 14.4, 12.0], "M", None),
        ],
    },
    "clue": {
        "palette": {"o": O, "b": (0xB0, 0x8A, 0x5A, 255), "B": (0x7A, 0x5A, 0x38, 255), "c": (0xD8, 0xC0, 0x90, 255),
                    "s": (0xF2, 0xC4, 0x9C, 255), "S": (0xD8, 0x9C, 0x74, 255), "a": (0xFF, 0xD2, 0x3C, 255),
                    "k": (0x14, 0x14, 0x18, 255), "w": (0xF4, 0xF2, 0xEC, 255), "m": (0x5A, 0x3A, 0x22, 255)},
        "icon": [
            "................",
            ".....oooooo.....",
            "....obcbcbbo....",
            "...obcbcbcbbo...",
            ".oobbbbbbbbbboo.",
            ".oBBBBBBBBBBBBo.",
            "..ossssssssso...",
            "..oswksssaaao...",
            "..osssssawkao...",
            "..ossssssaaao...",
            "..osmmmmmmsso...",
            "..ossmmmmssso...",
            "...osssssssoo...",
            "....ooooooo.....",
            "................",
            "................",
        ],
        "face": [
            "bbcbcbcbcbcbcbbb",
            "bcbcbcbcbcbcbcbb",
            "BBBBBBBBBBBBBBBB",
            "ssssssssssssssss",
            "sswwwsssssaaaaas",
            "sswkwssssaawkaas",
            "ssssssssssaaaaas",
            "ssssssssssssssss",
            "sSssssssSSsssssS",
            "ssssssssssssssss",
            "ssmmmmmmmmmmmmss",
            "smmmmmssssmmmmms",
            "ssssssssssssssss",
            "ssssssssssssssss",
            "sSssssssssssssSs",
            "ssssssssssssssss",
        ],
        "plate": "s",
        "parts": [
            ([0.6, 14.4, 0.6], [15.4, 18.0, 15.4], "b", None),       # deerstalker cap
            ([2.0, 18.0, 2.0], [14.0, 20.0, 14.0], "c", None),
            ([5.0, 13.0, -3.0], [11.0, 14.4, 0.6], "B", None),        # front brim
            ([5.0, 13.0, 15.4], [11.0, 14.4, 19.0], "B", None),       # back brim
            ([10.0, 8.0, -2.0], [14.0, 12.0, -1.2], "a", None),       # monocle
            ([12.5, 2.0, -1.8], [13.0, 8.0, -1.4], "a", None),        # monocle chain
        ],
    },
    "sentinel": {
        "palette": {"o": O, "m": (0x5A, 0x62, 0x78, 255), "M": (0x34, 0x3A, 0x4C, 255), "c": (0xFF, 0x3A, 0x4C, 255),
                    "C": (0xFF, 0xA0, 0xA8, 255), "a": (0xFF, 0xC9, 0x3C, 255), "k": (0x12, 0x12, 0x18, 255)},
        "icon": [
            "..o..........o..",
            "..oo........oo..",
            "...om......mo...",
            "...oooooooooo...",
            "..ommmmmmmmmmo..",
            ".ommmmmmmmmmmmo.",
            ".oMMkkkkkkkkMMo.",
            ".oMkcccCCcccckMo",
            ".oMMkkkkkkkkMMo.",
            ".ommmmmaammmmmo.",
            ".ommmmmaammmmmo.",
            "..oMmmmmmmmmMo..",
            "...oMMmmmmMMo...",
            "....oooooooo....",
            "................",
            "................",
        ],
        "face": [
            "mmmmmmmmmmmmmmmm",
            "mMmmmmmmmmmmmmMm",
            "mmmmmmmaammmmmmm",
            "mmmmmmmaammmmmmm",
            "kkkkkkkkkkkkkkkk",
            "kcccccCCCCcccccc",
            "kkkkkkkkkkkkkkkk",
            "mmmmmmmmmmmmmmmm",
            "mMmmmmmmmmmmmmMm",
            "mmmmmmmmmmmmmmmm",
            "mmmMMMMMMMMMMmmm",
            "mmmMkMkMkMkMMmmm",
            "mmmMMMMMMMMMMmmm",
            "mmmmmmmmmmmmmmmm",
            "MMmmmmmmmmmmmmMM",
            "MMMMMMMMMMMMMMMM",
        ],
        "plate": "m",
        "parts": [
            ([1.5, 14.4, 6.0], [2.5, 21.0, 7.0], "M", (2, 14.4, 6.5, "z", 22.5)),    # antennae
            ([13.5, 14.4, 6.0], [14.5, 21.0, 7.0], "M", (14, 14.4, 6.5, "z", -22.5)),
            ([1.0, 20.0, 5.5], [3.0, 22.0, 7.5], "c", None),
            ([13.0, 20.0, 5.5], [15.0, 22.0, 7.5], "c", None),
            ([0.6, 6.0, 1.0], [1.6, 13.0, 13.0], "M", None),                       # side plates
            ([14.4, 6.0, 1.0], [15.4, 13.0, 13.0], "M", None),
            ([2.0, 9.0, -2.0], [14.0, 11.0, -1.2], "c", None),                     # glowing visor
        ],
    },
    "anonymous": {
        "palette": {"o": O, "w": (0xF4, 0xF0, 0xE6, 255), "W": (0xD0, 0xC8, 0xB8, 255), "k": (0x14, 0x14, 0x18, 255),
                    "r": (0xE8, 0x7A, 0x8A, 255), "h": (0x2A, 0x2A, 0x34, 255), "H": (0x18, 0x18, 0x20, 255)},
        "icon": [
            "....oooooooo....",
            "...ohhhhhhhho...",
            "..ohHHHHHHHHho..",
            ".ohHowwwwwwoHho.",
            ".ohowwwwwwwwoho.",
            ".oowwkkwwkkwwoo.",
            "..owrwwwwwwrwo..",
            "..owwwwWwwwwwo..",
            "..owkkkkkkkkwo..",
            "..owwkwwwwkwwo..",
            "...owwwkkwwwo...",
            "...owwwkkwwwo...",
            "....owwwwwwo....",
            ".....oooooo.....",
            "................",
            "................",
        ],
        "face": [
            "wwwwwwwwwwwwwwww",
            "wwwkkkwwwwkkkwww",
            "wwkwwwwwwwwwwkww",
            "wwwwwwwwwwwwwwww",
            "wwkkkkwwwwkkkkww",
            "wwwkkwwwwwwkkwww",
            "wrwwwwwwwwwwwwrw",
            "wwwwwwwWWwwwwwww",
            "wwwwwwwWWwwwwwww",
            "wkwwwwwwwwwwwwkw",
            "wkkkkkkkkkkkkkkw",
            "wwwkwwwwwwwwkwww",
            "wwwwwwwkkwwwwwww",
            "wwwwwwwkkwwwwwww",
            "WwwwwwwkkwwwwwwW",
            "WWwwwwwwwwwwwwWW",
        ],
        "plate": "w",
        "parts": [
            ([0.0, 3.0, 0.0], [16.0, 17.0, 15.0], "h", None),                       # hood around the head
            ([2.0, 15.0, 2.0], [14.0, 18.0, 15.0], "H", None),
        ],
    },
    "prisoner": {
        "palette": {"o": O, "r": (0xFF, 0x8A, 0x1E, 255), "R": (0xC8, 0x5A, 0x10, 255), "w": (0xF4, 0xF2, 0xEC, 255),
                    "k": (0x14, 0x14, 0x18, 255), "s": (0xF2, 0xC4, 0x9C, 255), "S": (0xD8, 0x9C, 0x74, 255),
                    "g": (0x9C, 0xA0, 0xA8, 255)},
        "icon": [
            "................",
            ".....oooooo.....",
            "....orrrrrro....",
            "...orRrrRrrRo...",
            "..orrrrrrrrrro..",
            "..oRRRRRRRRRRo..",
            "..ossssssssso...",
            "..oswkssswksso..",
            "..osssssssssso..",
            "..ossssSSsssso..",
            "..osssskksssso..",
            "...owwwwwwwwo...",
            "...owkwkwkwko...",
            "....oooooooo....",
            "................",
            "................",
        ],
        "face": [
            "rrrrrrrrrrrrrrrr",
            "rRrrRrrRrrRrrRrr",
            "RRRRRRRRRRRRRRRR",
            "ssssssssssssssss",
            "sswwwsssssswwwss",
            "sswkwsssssswkwss",
            "ssssssssssssssss",
            "sssssssSSsssssss",
            "ssssssssssssssss",
            "sssssskkkkssssss",
            "ssssssssssssssss",
            "gggggggggggggggg",
            "gwwwwwwwwwwwwwwg",
            "gwkwkkwkwkkwkwwg",
            "gwwwwwwwwwwwwwwg",
            "gggggggggggggggg",
        ],
        "plate": "s",
        "parts": [
            ([0.6, 14.4, 0.6], [15.4, 17.5, 15.4], "r", None),                      # beanie
            ([2.0, 17.5, 2.0], [14.0, 19.0, 14.0], "R", None),
            ([7.0, 19.0, 7.0], [9.0, 20.5, 9.0], "r", None),
            ([3.0, -2.0, -2.2], [13.0, 1.0, -1.2], "w", None),                     # number plate
        ],
    },
}


def tier_theme(name, rgb, horns):
    """Generic mask in a tier colour (no theme): a smooth face plate, horns from Legendary up."""
    import colorsys
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))

    def tone(dl):
        r, g, b = colorsys.hls_to_rgb(h, max(0.05, min(0.95, l + dl)), s)
        return (round(r * 255), round(g * 255), round(b * 255), 255)
    face = [
        "4444444444444444",
        "4h44444444444434",
        "4444444444444444",
        "44oooo4444oooo44",
        "4ookkoo44ookkoo4",
        "44oooo4444oooo44",
        "4444444444444434",
        "4444444hh4444444",
        "4444444444444434",
        "4344444444444434",
        "44o4444444444o44",
        "443oooooooooo344",
        "3443333333333443",
        "3344444444444433",
        "3333333333333333",
        "3333333333333333",
    ]
    parts = []
    if horns:
        parts += [([1.5, 13.0, 2.0], [3.5, 18.5, 4.0], "3", (2.5, 13, 3, "z", 22.5)),
                  ([12.5, 13.0, 2.0], [14.5, 18.5, 4.0], "3", (13.5, 13, 3, "z", -22.5))]
    icon_rows = [
        "................",
        "................",
        "...oooooooooo...",
        "..o4h444444443o.",
        ".o4444444444443o",
        ".o44oo4444oo443o",
        ".o4okko44okko43o",
        ".o44oo4444oo433o",
        ".o44444hh44443o.",
        "..o444444444332o",
        "..o44oooooo432o.",
        "...o443333322o..",
        "....oo33222oo...",
        "......ooooo.....",
        "................",
        "................",
    ]
    return {"palette": {"o": O, "k": (0x10, 0x10, 0x14, 255), "4": tone(0.0), "3": tone(-0.18), "2": tone(-0.3),
                        "h": tone(0.3)},
            "icon": icon_rows, "face": face, "plate": "4", "parts": parts}


SWATCH = 4  # px


def texture(theme):
    img = Image.new("RGBA", (32, 32), CLEAR)
    pal = theme["palette"]
    for y, row in enumerate(theme["face"]):
        assert len(row) == 16, (row, len(row))
        for x, ch in enumerate(row):
            img.putpixel((x, y), pal[ch])
    keys = sorted(pal)
    for i, ch in enumerate(keys):
        px, py = 16 + (i % 4) * SWATCH, (i // 4) * SWATCH
        for dx in range(SWATCH):
            for dy in range(SWATCH):
                img.putpixel((px + dx, py + dy), pal[ch])
    return img, {ch: i for i, ch in enumerate(keys)}


def swatch_uv(index):
    # model uv space is 0..16 over the 32 px texture: 1 px = 0.5 uv
    u, v = 8 + (index % 4) * 2, (index // 4) * 2
    return [u + 0.5, v + 0.5, u + 1.5, v + 1.5]


def element(frm, to, uv, rotation=None, north_uv=None):
    faces = {d: {"uv": uv, "texture": "#mask"} for d in ("north", "south", "east", "west", "up", "down")}
    if north_uv:
        faces["north"] = {"uv": north_uv, "texture": "#mask"}
    e = {"from": frm, "to": to, "faces": faces}
    if rotation:
        ox, oy, oz, axis, angle = rotation
        e["rotation"] = {"origin": [ox, oy, oz], "axis": axis, "angle": angle}
    return e


def worn_model(name, theme, index):
    plate = element([1.0, 1.0, -1.2], [15.0, 15.0, -0.2], swatch_uv(index[theme["plate"]]), north_uv=[0, 0, 8, 8])
    elements = [plate]
    for frm, to, ch, rot in theme["parts"]:
        elements.append(element(frm, to, swatch_uv(index[ch]), rot))
    return {"textures": {"mask": "theprisons:item/prisons/mask_worn/" + name, "particle": "theprisons:item/prisons/mask_worn/" + name},
            "elements": elements}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def emit_worn(name, theme):
    img, index = texture(theme)
    path = os.path.join(OUT, "textures", "item", "prisons", "mask_worn", name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    write_json(os.path.join(OUT, "models", "item", "prisons", "mask_worn", name + ".json"), worn_model(name, theme, index))
    write_json(os.path.join(OUT, "items", "prisons", "mask_worn", name + ".json"),
               {"model": {"type": "minecraft:model", "model": "theprisons:item/prisons/mask_worn/" + name}})


def icon(theme):
    img = Image.new("RGBA", (16, 16), CLEAR)
    for y, row in enumerate(theme["icon"]):
        assert len(row) == 16, (row, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), theme["palette"][ch])
    return img
