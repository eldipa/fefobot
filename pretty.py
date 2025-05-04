import json, sys, pprint

if __name__ == '__main__':
    with open(sys.argv[1], 'rt') as src:
        main = json.load(src)
        for rule, matches_str in main.items():
            print(rule)
            print("-" * len(rule))
            print()
            pprint.pprint(json.loads(matches_str))
            print()
