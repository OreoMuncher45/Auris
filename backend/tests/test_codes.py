from app import codes


def test_generated_codes_are_six_valid_characters():
    for _ in range(200):
        code = codes.new_code()
        assert len(code) == codes.CODE_LENGTH
        assert codes.is_valid(code)


def test_alphabet_excludes_the_confusable_letters():
    assert "I" not in codes.ALPHABET
    assert "L" not in codes.ALPHABET
    assert "O" not in codes.ALPHABET


def test_typed_codes_are_normalised_rather_than_rejected():
    assert codes.normalise(" ab-c d2 ") == "ABCD2"
    assert codes.normalise("olive1") == "011VE1"
    assert codes.normalise("iIlLoO") == "111100"


def test_is_valid_rejects_the_wrong_shape():
    assert not codes.is_valid("ABC12")
    assert not codes.is_valid("ABC1234")
    assert not codes.is_valid("ABCDEI")  # I is not in the alphabet
